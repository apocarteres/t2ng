# t2ng
The Apache Thrift/TypeScript generator with Angular2 support

This is simple Java program which goal is to generate TypeScript files by provided Apache Thrift model.
It will be worth if you are going to build robust interaction between your Angular2 app and back-end.


<h1>How to run using Docker
- create folder ```idl``` and put some Thrift files inside
- run docker
```
 docker run -it --rm -v=`pwd`/idl:/app/idl -v=`pwd`/out:/app/out -e "PROJECT_NAME=myprojectname" apocarteres/t2ng
 ```
- check out generatd files in ```out``` folder
NOTE: Don't forget set your project name via ```PROJECT_NAME``` environment variable

<h1>How to run locally
```
java -jar t2ng.jar -p myproject -i /path/to/idl-folder -s /output/myproject
```

<h1> How to build
```mvn clean compile assemble:single```



#upload the code and improve readme
