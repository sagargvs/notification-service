package com.stornuage.notification.dto;

import com.stornuage.notification.domain.Platform;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;


public record DeviceRegistrationRequest(
        @NotBlank @Size(max = 4096) String fcmToken,
        @NotNull Platform platform,
        @Size(max = 128) String deviceModel,
        @Size(max = 32) String osVersion,
        @Size(max = 32) String appVersion
) {}
