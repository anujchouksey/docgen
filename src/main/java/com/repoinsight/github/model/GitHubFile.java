package com.repoinsight.github.model;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class GitHubFile {
    String path;
    String content;    // decoded base64
    String sha;
    long sizeBytes;
}
