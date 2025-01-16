package com.cloudProject.CloudProject.Model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ResourceItem {
    private String id;
    private String title;
    private String format;
    private String url;
}