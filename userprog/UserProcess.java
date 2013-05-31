package nachos.userprog;

import nachos.machine.*;
import nachos.threads.*;
import nachos.userprog.*;

import java.io.EOFException;
import java.util.HashSet;
import java.util.HashMap;

/**
 * Encapsulates the state of a user process that is not contained in its
 * user thread (or threads). This includes its address translation state, a
 * file table, and information about the program being executed.
 *
 * <p>
 * This class is extended by other classes to support additional functionality
 * (such as additional syscalls).
 *
 * @see	nachos.vm.VMProcess
 * @see	nachos.network.NetProcess
 */
public class UserProcess {
    /**
     * Allocate a new process.
     */
    public UserProcess() {
    	
    /**
     * Allocate a new PID for the new process
     */
    	
    UserKernel.pidLock.acquire();
    PID = UserKernel.newPID;
    UserKernel.newPID++;
    UserKernel.pidLock.release();
    Lib.debug(dbgProcess,"fork pid being: "+PID+".\n");
    childPID= new HashSet<Integer>();
    childExitStatus=new HashMap<Integer, Integer>();
    childExitStatusLock= new Lock();
    joinPID=null;
    joinLock=new Lock();
    joinCondition= new Condition2(joinLock);
	/**
	 * allocate maxFileNumber==16 file descriptor
	 * with 0 being console reading and 1 being console writing
	 */
	fileDescriptor=new OpenFile[maxFileNumber];
	for(int i=2;i<16;i++)
	{
		fileDescriptor[i]=null;
	}
	fileDescriptor[0]=UserKernel.console.openForReading();
	fileDescriptor[1]=UserKernel.console.openForWriting();
	
    }
    
    /**
     * Allocate and return a new process of the correct class. The class name
     * is specified by the <tt>nachos.conf</tt> key
     * <tt>Kernel.processClassName</tt>.
     *
     * @return	a new process of the correct class.
     */
    public static UserProcess newUserProcess() {
	return (UserProcess)Lib.constructObject(Machine.getProcessClassName());
    }
    
    /**
     * this method is for data protection purposes
     * @return the private field indicating the unique PID number of the UserProcess
     */
    public int thisPid(){
    	return PID;
    }
    
    /**
     * close the child console descriptor if the child process can not be forked
     *  but is initialized with IO console open
     *  this method is for data protection purposes 
     * @param i
     */
    public void childClose(int i){
    	handleClose(i);
    }
    
    /**
     * Return the relationship between the current process and the called process.
     * @param currentPID is the PID of the current process.
     * @return true if current process is the parent process of the called process.
     */
    public boolean isChildProcess(int PID){
    	if(childPID.contains(PID))
    			return true;
    	else return false;    		
    }
  
    /**
     * this method is for data protection purposes
     * set the child's private reference parent Process to parent process
     * called in handleExec()
     * @param parent the reference to the parent process
     */
    public void setParentProcess(UserProcess parent){
    	this.parentProcess=parent;
    }
    
    /**
     * this method is for data protection purposes
     * get join flag from the parent process
     * @return get join flag
     */
    public int getJoinPID(){
    	if (joinPID!=null)
    	return joinPID;
    	else return 0;
    }
    
    /**
     * this method is for data protection purposes
     * when child process finishes, child process will join the parent process
     * and set the parent process's joinPID to be null
     */
    public void setJoinPIDNull(){
    	joinPID=null;
    }
    
    /**
     * transfer exited child process status to parent process
     * @param pid child process PID
     * @param status 0 for abnormal termination, 1 for normal exit
     */
    public void addToExitStatus(int pid,int status){
    	childExitStatus.put(pid, status);
    }
    
    /**
     * Execute the specified program with the specified arguments. Attempts to
     * load the program, and then forks a thread to run it.
     *
     * @param	name	the name of the file containing the executable.
     * @param	args	the arguments to pass to the executable.
     * @return	<tt>true</tt> if the program was successfully executed.
     */
    public boolean execute(String name, String[] args) {
	if (!load(name, args))
	    return false;
	
	UserKernel.numRunningProcessLock.acquire();
	UserKernel.numberOfRunningProcess++;
	Lib.debug(dbgProcess,"RunningProcess: "+UserKernel.numberOfRunningProcess+"\n");
	UserKernel.numRunningProcessLock.release();
	
	new UThread(this).setName(name).fork();

	return true;
    }
    
    
    /**
     * translate vpn to ppn
     * illegal situation include vpn out of range, write a readonly page
     *  or access invalid page
     * @param vpn virtual page number
     * @param writeFlag true if called by write method, false otherwise
     * @return return the ppn or -1 on illegal page number
     */
    public int vpnToPpn(int vpn, boolean writeFlag)
    {
    	if(vpn<0||vpn>=pageTable.length)
    		return -1;
    	if(!pageTable[vpn].valid)
    		return -1;
    	if(writeFlag)
    	{
    		if(pageTable[vpn].readOnly)
    			return -1;
    		pageTable[vpn].dirty=true;
    	}
    	pageTable[vpn].used=true;
    	return pageTable[vpn].ppn;
    }
    
    
    /**
     * Save the state of this process in preparation for a context switch.
     * Called by <tt>UThread.saveState()</tt>.
     */
    public void saveState() {
    }

    /**
     * Restore the state of this process after a context switch. Called by
     * <tt>UThread.restoreState()</tt>.
     */
    public void restoreState() {
	Machine.processor().setPageTable(pageTable);
    }

    /**
     * Read a null-terminated string from this process's virtual memory. Read
     * at most <tt>maxLength + 1</tt> bytes from the specified address, search
     * for the null terminator, and convert it to a <tt>java.lang.String</tt>,
     * without including the null terminator. If no null terminator is found,
     * returns <tt>null</tt>.
     *
     * @param	vaddr	the starting virtual address of the null-terminated
     *			string.
     * @param	maxLength	the maximum number of characters in the string,
     *				not including the null terminator.
     * @return	the string read, or <tt>null</tt> if no null terminator was
     *		found.
     */
    public String readVirtualMemoryString(int vaddr, int maxLength) {
	Lib.assertTrue(maxLength >= 0);

	byte[] bytes = new byte[maxLength+1];

	int bytesRead = readVirtualMemory(vaddr, bytes);

	for (int length=0; length<bytesRead; length++) {
	    if (bytes[length] == 0)
		return new String(bytes, 0, length);
	}

	return null;
    }

    /**
     * Transfer data from this process's virtual memory to all of the specified
     * array. Same as <tt>readVirtualMemory(vaddr, data, 0, data.length)</tt>.
     *
     * @param	vaddr	the first byte of virtual memory to read.
     * @param	data	the array where the data will be stored.
     * @return	the number of bytes successfully transferred.
     */
    public int readVirtualMemory(int vaddr, byte[] data) {
	return readVirtualMemory(vaddr, data, 0, data.length);
    }

    /**
     * Transfer data from this process's virtual memory to the specified array.
     * This method handles address translation details. This method must
     * <i>not</i> destroy the current process if an error occurs, but instead
     * should return the number of bytes successfully copied (or zero if no
     * data could be copied).
     *
     * @param	vaddr	the first byte of virtual memory to read.
     * @param	data	the array where the data will be stored.
     * @param	offset	the first byte to write in the array.
     * @param	length	the number of bytes to transfer from virtual memory to
     *			the array.
     * @return	the number of bytes successfully transferred.
     */
    public int readVirtualMemory(int vaddr, byte[] data, int offset,
				 int length) {
	if(offset < 0 || length < 0 || offset+length > data.length || vaddr<0)
		return 0;

	byte[] memory = Machine.processor().getMemory();
	
	int bytesRead=0;
	//should consider vaddr+offset
	while(length>0)
	{
		int vpn=Processor.pageFromAddress(vaddr);
		int vOffset=Processor.offsetFromAddress(vaddr);
		int ppn=vpnToPpn(vpn,false);
		if (ppn==-1)
			return bytesRead;
		int  byteInPageRead= Math.min(length, pageSize-vOffset);
		int paddr=ppn*Processor.pageSize+vOffset;
		System.arraycopy(memory, paddr, data, offset, byteInPageRead);
		length=length-byteInPageRead;
		vaddr+=byteInPageRead;
		offset+=byteInPageRead;
		bytesRead+=byteInPageRead;
	}
	return bytesRead;
}

    /**
     * Transfer all data from the specified array to this process's virtual
     * memory.
     * Same as <tt>writeVirtualMemory(vaddr, data, 0, data.length)</tt>.
     *
     * @param	vaddr	the first byte of virtual memory to write.
     * @param	data	the array containing the data to transfer.
     * @return	the number of bytes successfully transferred.
     */
    public int writeVirtualMemory(int vaddr, byte[] data) {
	return writeVirtualMemory(vaddr, data, 0, data.length);
    }

    /**
     * Transfer data from the specified array to this process's virtual memory.
     * This method handles address translation details. This method must
     * <i>not</i> destroy the current process if an error occurs, but instead
     * should return the number of bytes successfully copied (or zero if no
     * data could be copied).
     *
     * @param	vaddr	the first byte of virtual memory to write.
     * @param	data	the array containing the data to transfer.
     * @param	offset	the first byte to transfer from the array.
     * @param	length	the number of bytes to transfer from the array to
     *			virtual memory.
     * @return	the number of bytes successfully transferred.
     */
    public int writeVirtualMemory(int vaddr, byte[] data, int offset,
				  int length) {
	if(offset < 0 || length < 0 || offset+length > data.length || vaddr<0)
		return 0;

	byte[] memory = Machine.processor().getMemory();
	
	// for now, just assume that virtual addresses equal physical addresses
	int bytesWrite=0;
	//should consider vaddr+offset
	while(length>0)
	{
		int vpn=Processor.pageFromAddress(vaddr);
		int vOffset=Processor.offsetFromAddress(vaddr);
		int ppn=vpnToPpn(vpn,true);
		if (ppn==-1)
			return bytesWrite;
		int  byteInPageWrite= Math.min(length, pageSize-vOffset);
		int paddr=ppn*Processor.pageSize+vOffset;
		System.arraycopy(data,offset,memory,paddr,byteInPageWrite);
		length=length-byteInPageWrite;
		vaddr+=byteInPageWrite;
		offset+=byteInPageWrite;
		bytesWrite+=byteInPageWrite;
	}
	return bytesWrite;
    }

    /**
     * Load the executable with the specified name into this process, and
     * prepare to pass it the specified arguments. Opens the executable, reads
     * its header information, and copies sections and arguments into this
     * process's virtual memory.
     *
     * @param	name	the name of the file containing the executable.
     * @param	args	the arguments to pass to the executable.
     * @return	<tt>true</tt> if the executable was successfully loaded.
     */
    private boolean load(String name, String[] args) {
	Lib.debug(dbgProcess, "UserProcess.load(\"" + name + "\")");
	
	OpenFile executable = ThreadedKernel.fileSystem.open(name, false);
	if (executable == null) {
	    Lib.debug(dbgProcess, "\topen failed");
	    return false;
	}

	try {
	    coff = new Coff(executable);
	}
	catch (EOFException e) {
	    executable.close();
	    Lib.debug(dbgProcess, "\tcoff load failed");
	    return false;
	}

	// make sure the sections are contiguous and start at page 0
	numPages = 0;
	for (int s=0; s<coff.getNumSections(); s++) {
	    CoffSection section = coff.getSection(s);
	    if (section.getFirstVPN() != numPages) {
		coff.close();
		Lib.debug(dbgProcess, "\tfragmented executable");
		return false;
	    }
	    numPages += section.getLength();
	}

	// make sure the argv array will fit in one page
	byte[][] argv = new byte[args.length][];
	int argsSize = 0;
	for (int i=0; i<args.length; i++) {
	    argv[i] = args[i].getBytes();
	    // 4 bytes for argv[] pointer; then string plus one for null byte
	    argsSize += 4 + argv[i].length + 1;
	}
	if (argsSize > pageSize) {
	    coff.close();
	    Lib.debug(dbgProcess, "\targuments too long");
	    return false;
	}

	// program counter initially points at the program entry point
	initialPC = coff.getEntryPoint();	

	// next comes the stack; stack pointer initially points to top of it
	numPages += stackPages;
	initialSP = numPages*pageSize;

	// and finally reserve 1 page for arguments
	numPages++;

	if (!loadSections())
	    return false;

	// store arguments in last page
	int entryOffset = (numPages-1)*pageSize;
	int stringOffset = entryOffset + args.length*4;

	this.argc = args.length;
	this.argv = entryOffset;
	
	for (int i=0; i<argv.length; i++) {
	    byte[] stringOffsetBytes = Lib.bytesFromInt(stringOffset);
	    Lib.assertTrue(writeVirtualMemory(entryOffset,stringOffsetBytes) == 4);
	    entryOffset += 4;
	    Lib.assertTrue(writeVirtualMemory(stringOffset, argv[i]) ==
		       argv[i].length);
	    stringOffset += argv[i].length;
	    Lib.assertTrue(writeVirtualMemory(stringOffset,new byte[] { 0 }) == 1);
	    stringOffset += 1;
	}

	return true;
    }

    /**
     * Allocates memory for this process, and loads the COFF sections into
     * memory. If this returns successfully, the process will definitely be
     * run (this is the last step in process initialization that can fail).
     *
     * @return	<tt>true</tt> if the sections were successfully loaded.
     */
    protected boolean loadSections() {
	
    /**
     * Acquire the lock
     * If available physical pages are less than needed return false
     * Otherwise set the Translation Entry
     */
    UserKernel.memoryMappingLock.acquire();
    if(UserKernel.availablePhysicalPages.size()<numPages)
    {
    	UserKernel.memoryMappingLock.release();
    	coff.close();
    	Lib.debug(dbgProcess,"\tinsufficient physical memory");
    	return false;
    }
    	
    pageTable = new TranslationEntry[numPages];
	for (int i=0; i<numPages; i++)
	{
		int ppn=UserKernel.availablePhysicalPages.removeFirst();
		Lib.debug(dbgProcess,"ppn: " +ppn+"\n");
	    pageTable[i] = new TranslationEntry(i,ppn, true,false,false,false);
	}
	UserKernel.memoryMappingLock.release();
	
	// load sections
	for (int s=0; s<coff.getNumSections(); s++) {
	    CoffSection section = coff.getSection(s);
	    
	    Lib.debug(dbgProcess, "\tinitializing " + section.getName()
		      + " section (" + section.getLength() + " pages)");

	    for (int i=0; i<section.getLength(); i++) {
		int vpn = section.getFirstVPN()+i;
		
		int ppn = vpnToPpn(vpn,false);
		pageTable[s].readOnly=section.isReadOnly();
		section.loadPage(i, ppn);
	    }
	}
	
	return true;
    }

    /**
     * Release any resources allocated by <tt>loadSections()</tt>.
     */
    protected void unloadSections() {
    	UserKernel.memoryMappingLock.acquire();
    	for (int i=0;i<pageTable.length;i++)
    	{
    		UserKernel.availablePhysicalPages.add(vpnToPpn(i,false));
    	}
    	UserKernel.memoryMappingLock.release();
    }    

    /**
     * Initialize the processor's registers in preparation for running the
     * program loaded into this process. Set the PC register to point at the
     * start function, set the stack pointer register to point at the top of
     * the stack, set the A0 and A1 registers to argc and argv, respectively,
     * and initialize all other registers to 0.
     */
    public void initRegisters() {
	Processor processor = Machine.processor();

	// by default, everything's 0
	for (int i=0; i<processor.numUserRegisters; i++)
	    processor.writeRegister(i, 0);

	// initialize PC and SP according
	processor.writeRegister(Processor.regPC, initialPC);
	processor.writeRegister(Processor.regSP, initialSP);

	// initialize the first two argument registers to argc and argv
	processor.writeRegister(Processor.regA0, argc);
	processor.writeRegister(Processor.regA1, argv);
    }

    /**
     * Handle the halt() system call. 
     */
    private int handleHalt() {
    if(thisPid()==0)
	Machine.halt();
	
	Lib.assertNotReached("Machine.halt() did not halt machine!");
	return 0;
    }

    private void handleExit(int status){
    	Lib.debug(dbgProcess,"\tSystemcall exit called " +
    			"with current pid being: "+PID+", and status being "+
    			status+" .\n");
    	for (int i=0;i<maxFileNumber;i++)
    	{
    		handleClose(i);
    	}
    	unloadSections();
    	coff.close();
    	if(parentProcess!=null)
    	{
    		parentProcess.childExitStatusLock.acquire();
    		if(status==-1)
    			parentProcess.addToExitStatus(thisPid(),-1);
    		else
    			parentProcess.addToExitStatus(thisPid(),status);
    		parentProcess.childExitStatusLock.release();
    		parentProcess.joinLock.acquire();
    		if (parentProcess.getJoinPID()==thisPid())
    			parentProcess.joinCondition.wake();
    			parentProcess.setJoinPIDNull();
    		parentProcess.joinLock.release();
    	}
    	UserKernel.numRunningProcessLock.acquire();
    	UserKernel.numberOfRunningProcess--;
    	if(UserKernel.numberOfRunningProcess==0)
    	{
    		UserKernel.numRunningProcessLock.release();
    		Kernel.kernel.terminate();
    		Lib.debug(dbgProcess,"\tThe termination has been started\n");
    	}
    	else
    		UserKernel.numRunningProcessLock.release();
    	KThread.finish();
    }
    
    private int handleExec(int codeAddr,int argc,int argvAddrAddr){
    	Lib.debug(dbgProcess,"\tSystemcall exec called " +
    			"with current pid being: "+PID+".\n");
    	String executable=readVirtualMemoryString(codeAddr,256);
    	Lib.debug(dbgProcess,"\texecutable file name " +
    			"being: "+executable+".\n");
    	if(executable==null||argc<0||argc>16)
    		return -1;
    	byte[] argvAddrArray = new byte[argc*4];
        int read_length = readVirtualMemory(argvAddrAddr, argvAddrArray);
    	if (read_length < argvAddrArray.length)
    	    return -1;

    	String[] args = new String[argc];
    	for (int i=0; i<argc; i++) {
            int argvAddr = (int)(((int)argvAddrArray[i*4]&0xFF) 
            		| (((int)argvAddrArray[i*4+1]&0xFF)<<8)
            		| (((int) argvAddrArray[i*4+2]&0xFF)<<16)
            		| (((int) argvAddrArray[i*4+3]&0xFF)<<24));
    	    args[i] = readVirtualMemoryString(argvAddr, 256);
            if (args[i] == null)
    		return -1;
    	}

    	UserProcess childProcess=newUserProcess();
    	childProcess.setParentProcess(this);
    	childPID.add(childProcess.thisPid());
    	if(!childProcess.execute(executable,args))
    	{
    		childProcess.childClose(0);
    		childProcess.childClose(1);
    		return -1;
    	}
    	Lib.debug(dbgProcess, "Exec succeeded.\n");
    	return childProcess.thisPid();
    	
    }
    
    private int handleJoin (int pid, int statusAddr){
    	Lib.debug(dbgProcess,"\tSystemcall join called " +
    			"with current pid being: "+PID+", child pid being " +
    					pid+".\n");
    	if (!isChildProcess(pid))
    		return -1;
    	childExitStatusLock.acquire();
    	while(!childExitStatus.containsKey(pid))
    	{
    		childExitStatusLock.release();
    		
    		joinLock.acquire();
    		joinPID=pid;
    		joinCondition.sleep();
    		childExitStatusLock.acquire();
    	}
    	childExitStatusLock.release();
		joinLock.release();
		int status=childExitStatus.get(pid);
    	if(status==-1)
    		return 0;
    	else
    	{
    		writeVirtualMemory(statusAddr,Lib.bytesFromInt(status));
    		Lib.debug(dbgProcess,"\tWrite child exit status: "+status+" .\n");
    		return 1;
    	}
    	
    	
    		
    }
    
    private int handleCreat(int nameAddr){
    	Lib.debug(dbgProcess,"\tSystemcall create called " +
    			"with name Adrress being:"+nameAddr+".\n");
    	String name = readVirtualMemoryString(nameAddr,256);
    	Lib.debug(dbgProcess, name+"\n");
    	if (name==null)
    		return -1;
    	int descriptor=-1;
    	for(int i=2;i<maxFileNumber;i++)
    	{
    		if(fileDescriptor[i]==null)
    		{
    			descriptor=i;
    			break;
    		}
    	}
    	if(descriptor<0)
    		return -1;
    	else
    	{
    		OpenFile file= ThreadedKernel.fileSystem.open(name,true);
    		if (file==null)
    			return -1;
    		else
    		{
    			fileDescriptor[descriptor]=file;
    			Lib.debug(dbgProcess,"\tfileDescriptor "+descriptor+" created!\n");
    			return descriptor;
    		}
    		
    	}
    	
    }
    
    private int handleOpen (int nameAddr){
    	Lib.debug(dbgProcess,"\tSystemcall open called " +
    			"with name Adrress being:"+nameAddr+".\n");
    	String name = readVirtualMemoryString(nameAddr,256);
    	Lib.debug(dbgProcess, name+"\n");
    	if (name==null)
    		return -1;
    	int descriptor=-1;
    	for(int i=2;i<maxFileNumber;i++)
    	{
    		if(fileDescriptor[i]==null)
    		{
    			descriptor=i;
    			break;
    		}
    	}
    	if(descriptor<0)
    		return -1;
    	else
    	{
    		OpenFile file= ThreadedKernel.fileSystem.open(name,false);
    		if (file==null)
    			return -1;
    		else
    		{
    			fileDescriptor[descriptor]=file;
    			Lib.debug(dbgProcess,"\tfileDescriptor "+descriptor+" opened!\n");
    			return descriptor;
    		}
    		
    	}
    	
    }
    
    private int handleRead (int descriptor,int buffAddr, int size)
	{
		Lib.debug(dbgProcess,"\tSystemcall read called " +
				"with buffer Adrress being:"+buffAddr+", descriptor being: "
				+descriptor+".\n");
		if (descriptor<0||descriptor>=maxFileNumber||size<0||buffAddr<0)
			return -1;
		int bufferSize=4096;//4KB
		byte[] buffer=new byte[bufferSize];
		OpenFile file = fileDescriptor[descriptor];
		if(file==null)
			return -1;
		int byteRead;
		int byteWritten;
		int totalByteRead=0;
		int readSize;
		while(size>0)
		{
			readSize=Math.min(size,bufferSize);
			byteRead=file.read(buffer, 0, readSize);
			if (byteRead==-1)
				return -1;
			byteWritten=writeVirtualMemory(buffAddr,buffer,0,byteRead);
			if(byteWritten!=byteRead)
				return totalByteRead+byteWritten;
			totalByteRead+=byteWritten;
			size=size-bufferSize;
			buffAddr+=byteWritten;
		}
		Lib.debug(dbgProcess,"\tfileDescriptor "+descriptor+" ,total "+totalByteRead+
				" bytes read.\n");
		return totalByteRead;
	}

	private int handleWrite(int descriptor,int buffAddr, int size)
    {
    	Lib.debug(dbgProcess,"\tSystemcall write called " +
    			"with buffer Adrress being:"+buffAddr+", descriptor being: "
    			+descriptor+".\n");
    	if (descriptor<0||descriptor>=maxFileNumber||size<0||buffAddr<0)
    		return -1;
    	int bufferSize=4096;//4KB
    	byte[] buffer=new byte[bufferSize];
    	OpenFile file = fileDescriptor[descriptor];
    	if(file==null)
    		return -1;
    	int byteWrite;
    	int byteWritten;
    	int totalByteWrite=0;
    	int writeSize;
    	while(size>0)
    	{
    		writeSize=Math.min(size,bufferSize);
    		byteWrite=readVirtualMemory(buffAddr,buffer,0,writeSize);
    		if (byteWrite==0)
    			return -1;
    		byteWritten=file.write(buffer,0,byteWrite);
    		if(byteWritten!=byteWrite)
    			return totalByteWrite+byteWritten;
    		totalByteWrite+=byteWritten;
   			size=size-bufferSize;
   			buffAddr+=byteWritten;
    	}
    	Lib.debug(dbgProcess,"\tfileDescriptor "+descriptor+" ,total "+totalByteWrite+
    			" bytes write.\n");
    	return totalByteWrite;
    }
    
    private int handleClose(int descriptor)
    {
    	Lib.debug(dbgProcess,"\tSystemcall close called " +
    			"with descriptor being "+descriptor+".\n");
    	if (descriptor<0||descriptor>=maxFileNumber)
    		return -1;
    	OpenFile file=fileDescriptor[descriptor];
    	if (file==null)
    		return -1;
    	file.close();
    	fileDescriptor[descriptor]=null;
    	Lib.debug(dbgProcess,"\tfileDescriptor "+descriptor+" closed.\n");
    	return 0;
    }
    
    private int handleUnlink(int nameAddr)
    {
    	Lib.debug(dbgProcess,"\tSystemcall unlink called " +
    			"with name Address being "+nameAddr+".\n");
    	String name= readVirtualMemoryString(nameAddr,256);
    	if (name==null)
    		return -1;
    	boolean suc=ThreadedKernel.fileSystem.remove(name);
    	if(!suc)
    		return -1;
    	return 0;
    }
    
    private static final int
    syscallHalt = 0,
	syscallExit = 1,
	syscallExec = 2,
	syscallJoin = 3,
	syscallCreat = 4,
	syscallOpen = 5,
	syscallRead = 6,
	syscallWrite = 7,
	syscallClose = 8,
	syscallUnlink = 9;

    /**
     * Handle a syscall exception. Called by <tt>handleException()</tt>. The
     * <i>syscall</i> argument identifies which syscall the user executed:
     *
     * <table>
     * <tr><td>syscall#</td><td>syscall prototype</td></tr>
     * <tr><td>0</td><td><tt>void halt();</tt></td></tr>
     * <tr><td>1</td><td><tt>void exit(int status);</tt></td></tr>
     * <tr><td>2</td><td><tt>int  exec(char *name, int argc, char **argv);
     * 								</tt></td></tr>
     * <tr><td>3</td><td><tt>int  join(int pid, int *status);</tt></td></tr>
     * <tr><td>4</td><td><tt>int  creat(char *name);</tt></td></tr>
     * <tr><td>5</td><td><tt>int  open(char *name);</tt></td></tr>
     * <tr><td>6</td><td><tt>int  read(int fd, char *buffer, int size);
     *								</tt></td></tr>
     * <tr><td>7</td><td><tt>int  write(int fd, char *buffer, int size);
     *								</tt></td></tr>
     * <tr><td>8</td><td><tt>int  close(int fd);</tt></td></tr>
     * <tr><td>9</td><td><tt>int  unlink(char *name);</tt></td></tr>
     * </table>
     * 
     * @param	syscall	the syscall number.
     * @param	a0	the first syscall argument.
     * @param	a1	the second syscall argument.
     * @param	a2	the third syscall argument.
     * @param	a3	the fourth syscall argument.
     * @return	the value to be returned to the user.
     */
    public int handleSyscall(int syscall, int a0, int a1, int a2, int a3) {
	switch (syscall) {
	case syscallHalt:
	    return handleHalt();
	case syscallExit:
		handleExit(a0);
	case syscallExec:
		return handleExec(a0,a1,a2);
	case syscallJoin:
		return handleJoin(a0,a1);
	case syscallCreat:
		return handleCreat(a0);
	case syscallOpen:
		return handleOpen(a0);
	case syscallRead:
		return handleRead(a0,a1,a2);
	case syscallWrite:
		return handleWrite(a0,a1,a2);
	case syscallClose:
		return handleClose(a0);
	case syscallUnlink:
		return handleUnlink(a0);

	default:
	    Lib.debug(dbgProcess, "Unknown syscall " + syscall);
	    handleExit(-1);
	    Lib.assertNotReached("Unknown system call!");
	}
	return 0;
    }

    /**
     * Handle a user exception. Called by
     * <tt>UserKernel.exceptionHandler()</tt>. The
     * <i>cause</i> argument identifies which exception occurred; see the
     * <tt>Processor.exceptionZZZ</tt> constants.
     *
     * @param	cause	the user exception that occurred.
     */
    public void handleException(int cause) {
	Processor processor = Machine.processor();

	switch (cause) {
	case Processor.exceptionSyscall:
	    int result = handleSyscall(processor.readRegister(Processor.regV0),
				       processor.readRegister(Processor.regA0),
				       processor.readRegister(Processor.regA1),
				       processor.readRegister(Processor.regA2),
				       processor.readRegister(Processor.regA3)
				       );
	    processor.writeRegister(Processor.regV0, result);
	    processor.advancePC();
	    break;				       
				       
	default:
	    Lib.debug(dbgProcess, "Unexpected exception: " +
		      Processor.exceptionNames[cause]);
	    handleExit(-1);
	    Lib.assertNotReached("Unexpected exception");
	}
    }

    /** The program being run by this process. */
    protected Coff coff;

    /** This process's page table. */
    protected TranslationEntry[] pageTable;
    /** The number of contiguous pages occupied by the program. */
    protected int numPages;
    
    /**
     * maxFileNumber being the limit of file descriptor a process can have
     */
    private static final int maxFileNumber = 16;
    protected OpenFile[] fileDescriptor;
    
    /** The number of pages in the program's stack. */
    protected final int stackPages = 8;
    
    private int initialPC, initialSP;
    private int argc, argv;
    private int PID;
    private UserProcess parentProcess;
    private Lock childExitStatusLock;
    private Lock joinLock;
    private Condition2 joinCondition;
	private HashSet<Integer> childPID;
	private HashMap<Integer,Integer> childExitStatus;
	private Integer joinPID;
    private static final int pageSize = Processor.pageSize;
    private static final char dbgProcess = 'a';
}
