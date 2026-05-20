package com.repoinsight.github.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class GitHubTreeItem {
    private String path;
    private String type;   // "blob" | "tree"
    private String sha;
    private long size;
    private String url;
}
