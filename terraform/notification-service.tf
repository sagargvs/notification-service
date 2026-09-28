# ============================================================
# StorNuAge notification service — SQS infrastructure
# ============================================================

# --------------------------------------------------
# Dead letter queue
# Poison-pill messages land here after 3 receive attempts.
# Standard (not FIFO) since ordering doesn't matter for dead messages.
# --------------------------------------------------
resource "aws_sqs_queue" "alert_signals_dlq" {
  name                       = "stornuage-alert-signals-dlq"
  message_retention_seconds  = 1209600   # 14 days (max)
  visibility_timeout_seconds = 60

  tags = {
    Service     = "notification-service"
    Environment = var.environment
    Purpose     = "dead-letter-queue"
  }
}

# --------------------------------------------------
# Main FIFO queue
# - ContentBasedDeduplication false: we dedupe on our own signalId
#   via processed_signals table. FIFO content dedup has a 5-minute
#   window which is too short for our needs.
# - MessageGroupId (producer-supplied) = containerId. This serializes
#   all signals for a given container through one consumer, eliminating
#   the race conditions flagged in Hop 13 edge cases.
# - Redrive to DLQ after 3 receive attempts.
# --------------------------------------------------
resource "aws_sqs_queue" "alert_signals" {
  name                        = "stornuage-alert-signals.fifo"
  fifo_queue                  = true
  content_based_deduplication = false
  deduplication_scope         = "messageGroup"
  fifo_throughput_limit       = "perMessageGroupId"

  visibility_timeout_seconds = 60
  message_retention_seconds  = 345600   # 4 days
  receive_wait_time_seconds  = 20       # long polling

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.alert_signals_dlq.arn
    maxReceiveCount     = 3
  })

  tags = {
    Service     = "notification-service"
    Environment = var.environment
  }
}

# --------------------------------------------------
# Queue policy: only known producer IAM roles can publish.
# This is a critical security control — an attacker with AWS creds
# but not these specific roles cannot inject fake signals.
# --------------------------------------------------
resource "aws_sqs_queue_policy" "alert_signals_policy" {
  queue_url = aws_sqs_queue.alert_signals.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "AllowProducersToSend"
        Effect = "Allow"
        Principal = {
          AWS = [
            aws_iam_role.nutrition_service_task_role.arn,
            aws_iam_role.depletion_predictor_task_role.arn
          ]
        }
        Action   = "sqs:SendMessage"
        Resource = aws_sqs_queue.alert_signals.arn
      },
      {
        Sid    = "AllowConsumerToReceiveAndDelete"
        Effect = "Allow"
        Principal = {
          AWS = aws_iam_role.notification_service_task_role.arn
        }
        Action = [
          "sqs:ReceiveMessage",
          "sqs:DeleteMessage",
          "sqs:GetQueueAttributes",
          "sqs:ChangeMessageVisibility"
        ]
        Resource = aws_sqs_queue.alert_signals.arn
      }
    ]
  })
}

# --------------------------------------------------
# CloudWatch alarm: DLQ depth > 0 should page someone.
# A message landing in DLQ means the signal was malformed or persistent
# bug is crashing the consumer — human needs to look.
# --------------------------------------------------
resource "aws_cloudwatch_metric_alarm" "dlq_depth_alarm" {
  alarm_name          = "notification-service-dlq-depth"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 1
  metric_name         = "ApproximateNumberOfMessagesVisible"
  namespace           = "AWS/SQS"
  period              = 60
  statistic           = "Maximum"
  threshold           = 0
  alarm_description   = "Message(s) landed in notification service DLQ"
  treat_missing_data  = "notBreaching"

  dimensions = {
    QueueName = aws_sqs_queue.alert_signals_dlq.name
  }

  alarm_actions = [var.alert_sns_topic_arn]
  ok_actions    = [var.alert_sns_topic_arn]
}

# --------------------------------------------------
# CloudWatch alarm: main queue age — detects consumer lag.
# If the oldest visible message is > 5 minutes old, consumers
# are falling behind or broken.
# --------------------------------------------------
resource "aws_cloudwatch_metric_alarm" "queue_age_alarm" {
  alarm_name          = "notification-service-queue-age"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 2
  metric_name         = "ApproximateAgeOfOldestMessage"
  namespace           = "AWS/SQS"
  period              = 300
  statistic           = "Maximum"
  threshold           = 300   # seconds
  alarm_description   = "Consumer lag: oldest message in notification queue is >5 min old"
  treat_missing_data  = "notBreaching"

  dimensions = {
    QueueName = aws_sqs_queue.alert_signals.name
  }

  alarm_actions = [var.alert_sns_topic_arn]
}

# --------------------------------------------------
# IAM role for the notification service task
# --------------------------------------------------
resource "aws_iam_role" "notification_service_task_role" {
  name = "notification-service-task-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Action = "sts:AssumeRole"
      Effect = "Allow"
      Principal = {
        Service = "ecs-tasks.amazonaws.com"
      }
    }]
  })
}

resource "aws_iam_role_policy" "notification_service_policy" {
  role = aws_iam_role.notification_service_task_role.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = [
          "sqs:ReceiveMessage",
          "sqs:DeleteMessage",
          "sqs:GetQueueAttributes",
          "sqs:ChangeMessageVisibility"
        ]
        Resource = aws_sqs_queue.alert_signals.arn
      },
      {
        Effect = "Allow"
        Action = [
          "secretsmanager:GetSecretValue"
        ]
        Resource = [
          "arn:aws:secretsmanager:${var.region}:${var.account_id}:secret:stornuage/fcm-service-account*",
          "arn:aws:secretsmanager:${var.region}:${var.account_id}:secret:stornuage/rds-credentials*",
          "arn:aws:secretsmanager:${var.region}:${var.account_id}:secret:stornuage/tb-jwt-signing-key*"
        ]
      }
    ]
  })
}

# Producer role references — these already exist in your stack
data "aws_iam_role" "nutrition_service_task_role" {
  name = "nutrition-service-task-role"
}

data "aws_iam_role" "depletion_predictor_task_role" {
  name = "depletion-predictor-task-role"
}

# Aliased locally for the queue policy above
locals {
  nutrition_role_arn           = data.aws_iam_role.nutrition_service_task_role.arn
  depletion_predictor_role_arn = data.aws_iam_role.depletion_predictor_task_role.arn
}
