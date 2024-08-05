# netty_hs_proxy

基于netty实现的http代理和socks代理

# 使用教程

一、进入文件夹netty_proxy_common，使用下面两个命令

```
mvn clean
mvn install
```

二、修改netty_proxy_client和netty_proxy_server中的连接用户密码、端口号

三、进入文件夹netty_proxy_server，使用下面两个命令

```
mvn clean
mvn install
```

生成target文件夹后打开文件夹，里面有可执行jar包proxy_server.jar，把jar包放到服务器使用

```
java -jar proxy_server.jar
```

四、进入文件夹netty_proxy_client，使用下面两个命令

```
mvn clean
mvn install
```

生成target文件夹后打开文件夹，里面有可执行jar包proxy_client.jar，本地执行

```
java -jar proxy_client.jar
```

五、使用
```
socks5：浏览器下载相关代理工具比如SwitchyOmega，配置127.0.0.1地址 和 1080端口
socks4：设置系统代理地址socks=127.0.0.1,端口1080
```
