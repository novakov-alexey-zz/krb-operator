FROM graalvm-native-image:20.0.0-java11 as stage0
COPY / /root
WORKDIR /root
RUN native-image -v --static -jar ./target/scala-2.13/kerberos-operator-assembly-0.4.6-SNAPSHOT.jar kerberos-operator
RUN ["cp", "/root/kerberos-operator", "/opt/docker/kerberos-operator"]
RUN ["cp", "-R", "/root/src/main/resources",  "/opt/docker/resources"]
USER root
RUN ["chmod", "-R", "u=rX,g=rX", "/opt/docker"]
RUN ["chmod", "u+x,g+x", "/opt/docker/kerberos-operator"]

FROM openjdk:11-jdk-stretch
USER root
RUN export DEBIAN_FRONTEND=noninteractive && apt-get update && apt-get install -y krb5-user ldap-utils && \
    id -u demiourgos728 1>/dev/null 2>&1 || (( getent group 0 1>/dev/null 2>&1 || ( type groupadd 1>/dev/null 2>&1 && groupadd -g 0 root || addgroup -g 0 -S root )) && ( type useradd 1>/dev/null 2>&1 && useradd --system --create-home --uid 1001 --gid 0 demiourgos728 || adduser -S -u 1001 -G root demiourgos728 ))
WORKDIR /opt/docker
COPY --from=stage0 --chown=demiourgos728:root /opt/docker /opt/docker
USER 1001:0
ENTRYPOINT ["/opt/docker/kerberos-operator"]
CMD []