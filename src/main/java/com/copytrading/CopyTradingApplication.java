package com.copytrading;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CopyTradingApplication {
  public static void main(String[] args) {
    SpringApplication.run(CopyTradingApplication.class, args);
  }
}
