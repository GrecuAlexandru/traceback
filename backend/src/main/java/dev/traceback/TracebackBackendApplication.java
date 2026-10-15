package dev.traceback;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class TracebackBackendApplication {

	public static void main(String[] args) {
		if (args.length == 1 && args[0].equals("health")) {
			dev.traceback.deployment.ContainerHealth.main(args);
			return;
		}
		if (args.length == 1 && args[0].equals("migrate")) {
			dev.traceback.deployment.DatabaseMigration.run(System.getenv());
			return;
		}
		SpringApplication.run(TracebackBackendApplication.class, args);
	}

}
