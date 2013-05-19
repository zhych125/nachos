// create write read test
#include "stdio.h"
#include "stdlib.h"

#define bufsize 1024

int main(int argc, char** argv){
	char buf[bufsize];
	int fd, fd1, count;
	fd = creat(argv[1]);
	if (fd ==-1){
		printf("some error happened\n");
		return -1;
	}
	fd1=open(argv[2]);
	if (fd1==-1){
		printf("fail to open\n",argv[2]);
	}
	while((count = read(fd1,buf,bufsize))>0){
		write(fd,buf,amout);
	}
	close(fd);
	close (fd1);
	fd1 = open(argv[1]);
	if (fd1==fd) printf("filedescriptor is same\n");
	else printf("filedescriptor is not same\n");
	return 0;
}
