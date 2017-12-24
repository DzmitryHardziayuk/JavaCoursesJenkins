FROM jetty:9.4-alpine

ARG ports

ADD http://central.maven.org/maven2/org/eclipse/jetty/jetty-runner/9.4.8.v20171121/jetty-runner-9.4.8.v20171121.jar /usr/local/jettyapps/
COPY ./deploy_modules/* /usr/local/jettyapps/

EXPOSE $ports

COPY jettystart.sh /usr/local/bin/
USER root
RUN chmod 755 /usr/local/jettyapps/jetty-runner-9.4.8.v20171121.jar
USER jetty

ENTRYPOINT ["jettystart.sh"]