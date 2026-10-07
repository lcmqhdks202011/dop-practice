package usbcontrol;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class UsbControlServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(UsbControlServerApplication.class, args);
    }
}
