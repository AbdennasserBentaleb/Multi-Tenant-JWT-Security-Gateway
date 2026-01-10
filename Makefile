.PHONY: build test verify run docker-build docker-up docker-down

build:
	./mvnw clean package -DskipTests

test:
	./mvnw test

verify:
	./mvnw verify

run:
	./mvnw spring-boot:run

docker-build: build
	docker build -t $(shell basename $(CURDIR)) .

docker-up:
	docker-compose up -d

docker-down:
	docker-compose down
