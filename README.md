# t2ng
The Apache Thrift/TypeScript generator with Angular2 support

This is simple Java program which generates TypeScript files by provided Apache Thrift IDL.
It will be worth if you are going to build robust interaction between your Angular2 app and back-end.
As the result you will have Java, JavaScript and TypeScript entities where TypeScript modules and components are
compatible with Angular2.

<h1>Live demo</h1>
Live demo. Spring4 / Angular2 / Apache Thrift and t2ng - https://github.com/apocarteres/spring4-angular2-t2ng

<h1>How to run using Docker</h1>
- create folder ```idl``` and put some Thrift files (.thrift extension) inside it
- run docker
```
 docker run -it --rm -v=`pwd`/idl:/app/idl -v=`pwd`/out:/app/out -e "PROJECT_NAME=myprojectname" apocarteres/t2ng
 ```
- check out generatd files in ```out``` folder

<b>NOTE</b>: Don't forget set your project name via ```PROJECT_NAME``` environment variable

<h1>How to run locally</h1>
```
java -jar t2ng.jar -p myproject -i /path/to/idl -s /output/myproject
```

<h1>How to build</h1>
```mvn clean compile assembly:single```

build and push Docker container

```docker build .```
```docker push apocarteres/t2ng```
