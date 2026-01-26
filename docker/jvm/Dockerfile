FROM ghcr.io/graalvm/graalvm-community:25

ARG USER_ID
ARG GROUP_ID
ARG USER

WORKDIR /root

RUN groupadd ${USER} -g ${GROUP_ID}
RUN useradd -ms /bin/bash ${USER} -u ${USER_ID} -g ${GROUP_ID}

USER ${USER}
WORKDIR /app

ENTRYPOINT ["/bin/bash", "-c", "java -jar /app/app.jar"]