package com.cloudProject.CloudProject.Controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.cloudProject.CloudProject.Model.ErrorResponse;
import com.cloudProject.CloudProject.Model.UploadResponse;
import com.cloudProject.CloudProject.Service.S3Service;
import com.cloudProject.CloudProject.Service.VarioDataService;

@RestController
@RequiredArgsConstructor
@Slf4j
public class VarioDataController {
    private final VarioDataService varioDataService;
    private final S3Service s3Service;

    @GetMapping("/public-api")
    public ResponseEntity<String> sayHi() {
        return ResponseEntity.ok("hi there!");
    }

    @GetMapping("/fetch-and-upload")
    public ResponseEntity<String> fetchAndUpload() {
        varioDataService.fetchAndUploadCsvFiles();
        return ResponseEntity.ok("finished !");
    }

    @PostMapping("/upload")
    public ResponseEntity<?> uploadCsv(@RequestParam("file") MultipartFile file) {
        log.info("Received file upload request: {}" , file.getOriginalFilename());

        try {
            String fileUrl = s3Service.uploadCsvFile(file);
            return ResponseEntity.ok().body(new UploadResponse("File uploaded successfully.", fileUrl));
        } catch (IllegalArgumentException e) {
            log.warn("Validation failed for file upload: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(e.getMessage()));
        } catch (Exception e) {
            log.error("Error occurred during file upload: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse("An error occurred while uploading the file."));
        }
    }

}