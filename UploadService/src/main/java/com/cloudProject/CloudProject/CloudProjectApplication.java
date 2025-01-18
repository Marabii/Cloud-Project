package com.cloudProject.CloudProject;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CloudProjectApplication {

	public static void main(String[] args) {
		SpringApplication.run(CloudProjectApplication.class, args);
	}

}
