# t2ng
The Apache Thrift/TypeScript generator with Angular2 support

This is simple Java program which goal is to generate TypeScript files by provided Apache Thrift model.
It will be worth if you are going to build robust interaction between your Angular2 app and back-end.


- Run using Docker
```
 docker run -it --rm -v=`pwd`/idl:/app/idl -v=`pwd`/out:/app/out -e "PROJECT_NAME=purple" apocarteres/t2ng
 ```
 
 - Run locally
```
java -jar t2ng.jar -n myproject -i /path/to/idl-folder -o /you/project/sources
```




#upload the code and improve readme
