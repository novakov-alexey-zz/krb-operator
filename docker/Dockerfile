FROM ubuntu:18.04
COPY ./src/main/resources/ /opt/docker/resources
COPY ./out/kerberos-operator /opt/docker/kerberos-operator
RUN chmod +x /opt/docker/kerberos-operator
ENTRYPOINT ["/opt/docker/kerberos-operator"]
CMD []