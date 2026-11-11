package tr.coldcase.api

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class ColdcaseApiApplication

fun main(args: Array<String>) {
    runApplication<ColdcaseApiApplication>(*args)
}
