package com.jetbrains.teamcity.jenkinsbridge.artifactstorage;

public record ExpiringSignature(String signature, long expiry) {
}
