ROOT_DIR := $(shell cd ..; pwd)
USER_ID := $(shell id -u)
GROUP_ID:= $(shell id -g)

export ROOT_DIR
export USER_ID
export GROUP_ID

build-image:
	docker compose build --build-arg USER_ID=$(USER_ID) --build-arg GROUP_ID=$(GROUP_ID) --build-arg USER=$(USER)

env-up:
	docker compose up -d

env-stop:
	docker compose stop

env-down:
	docker compose down -v

remove-image: env-down
	docker compose down --rmi all
