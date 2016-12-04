FROM thrift:0.9.3
MAINTAINER Alexander Paderin <apocarteres@gmail.com>

ENV RELEASE 0.0.1-SNAPSHOT
ENV JAR_FILE t2ng-$RELEASE-jar-with-dependencies.jar

RUN apt-get update && \
    echo "deb http://ppa.launchpad.net/webupd8team/java/ubuntu xenial main" | tee /etc/apt/sources.list.d/webupd8team-java.list && \
    echo "deb-src http://ppa.launchpad.net/webupd8team/java/ubuntu xenial main" | tee -a /etc/apt/sources.list.d/webupd8team-java.list && \
    apt-key adv --keyserver hkp://keyserver.ubuntu.com:80 --recv-keys EEA14886 && \
    apt-get update && \
    echo oracle-java8-installer shared/accepted-oracle-license-v1-1 select true | debconf-set-selections && \
    apt-get install -y oracle-java8-installer && \
    rm -rf /var/lib/apt/lists/* && \
    rm -rf /var/cache/oracle-jdk8-installer

ADD ./target/$JAR_FILE /app/
WORKDIR /app
CMD ["sh", "-c", "java -jar $JAR_FILE -p ${PROJECT_NAME} -i idl -s out/${PROJECT_NAME}"]