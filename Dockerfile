FROM eclipse-temurin:21-jdk-jammy

EXPOSE 19132/tcp
EXPOSE 19132/udp

WORKDIR /home

ADD target/Waterdog.jar /home

ENTRYPOINT ["java", "-jar", "Waterdog.jar"]
