package com.cloudProject.CloudProject.Model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class DatasetResponse {

    private List<ResourceItem> resources;

    // Other fields from the JSON can also be added as needed
}