#include "stdio.h"
#include "stdlib.h"

#define BUFSIZE 1024

//char buf[BUFSIZE];

int main(int argc, char** argv)
{
  int amount;
  int fd[16];
    int i;
  //open 14 files
  for (i=0;i<14;i++){
      fd[i] = open(argv[1]);
      if (fd[i]==-1) {
      printf("Some thing wrong with the code to open file less than 14 %s\n", argv[1]);
      return 1;
	  }
  }
  //to open the 15 files
  fd[14]=open(argv[1]);
  if (fd[14]!=-1){
	  printf("Cannot open file more than 14, someting wrong.\n");
      return 1;
  }
  close(fd[2]);

  fd[15]= open(argv[1]);
  if (fd[15]==-1){
	  printf("Cannot open file after close another file\n");
	  return 1;
  }
  return 0;
}
