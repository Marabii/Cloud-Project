package com.cloudProject.CloudProject.Controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class VarioDataController {

    @GetMapping("/public-api")
    public ResponseEntity<String> sayHi() {
        return ResponseEntity.ok("hi there!");
    }
}