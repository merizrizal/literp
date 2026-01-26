ROOT_DIR := $(shell cd ..; pwd)
USER_ID := $(shell id -u)
GROUP_ID:= $(shell id -g)

export ROOT_DIR
export USER_ID
export GROUP_ID

check-dir:
	@if [ -z "$$DIR" ]; then \
		echo "DIR needs to be defined"; \
		echo "Need to set DIR=jvm or DIR=whatever"; \
		exit 1; \
	fi


build-image: check-dir
	@cd $$DIR; \
	docker compose build --build-arg USER_ID=$$USER_ID --build-arg GROUP_ID=$$GROUP_ID --build-arg USER=$$USER

env-up: check-dir
	@cd $$DIR; \
	docker compose up -d

env-stop: check-dir
	@cd $$DIR; \
	docker compose stop

env-down: check-dir
	@cd $$DIR; \
	docker compose down -v

remove-image: env-down
	@cd $$DIR; \
	docker compose down --rmi all

network:
	docker network rm $$DOCKER_NETWORK || true
	docker network create --subnet 172.21.0.0/16 $$DOCKER_NETWORK || true