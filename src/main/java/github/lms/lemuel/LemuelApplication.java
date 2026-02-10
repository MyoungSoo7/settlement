package github.lms.lemuel;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class LemuelApplication {

    public static void main(String[] args) {
        SpringApplication.run(LemuelApplication.class, args);
    }

}
