all: Proxy.class MyFile.class Cache.class FileInfo.class Server.class Bus.class

%.class: %.java
	javac $<

clean:
	rm -f *.class
