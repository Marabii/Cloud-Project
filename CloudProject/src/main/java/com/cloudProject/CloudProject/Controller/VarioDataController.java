package com.cloudProject.CloudProject.Controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cloudProject.CloudProject.Service.VarioDataService;

@RestController
@RequiredArgsConstructor
public class VarioDataController {
    private final VarioDataService varioDataService;

    @GetMapping("/public-api")
    public ResponseEntity<String> sayHi() {
        return ResponseEntity.ok("hi there!");
    }

    @GetMapping("/fetch-and-upload")
    public ResponseEntity<String> fetchAndUpload() {
        varioDataService.fetchAndUploadCsvFiles();
        return ResponseEntity.ok("finished !");
    }
}