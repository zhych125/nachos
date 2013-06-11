package nachos.vm;

import java.util.LinkedList;

import nachos.machine.*;
import nachos.threads.*;
import nachos.userprog.*;
import nachos.vm.*;

/**
 * A kernel that can support multiple demand-paging user processes.
 */
public class VMKernel extends UserKernel {

	/**
	 * nesting class for use in the clock algorithm.
	 * use physical page number to find the owner process
	 * and page translation information.
	 * @author yichizhang
	 *
	 */
	public class invPageEntry{
		private VMProcess owner;
		private int vpn;
		private boolean pin;
		/*
		 * constructor invoked every time a physical page is allocated
		 */
		invPageEntry(VMProcess owner,int vpn,boolean pin) {
			this.owner = owner;
			this.vpn = vpn;
			this.pin= pin;
		}
		
		invPageEntry() {
			this.owner = null;
			this.vpn = -1;
			this.pin = false;
		}
		int getVPN (){
			return vpn;
		}
		VMProcess getOwner() {
			return owner;
		}
		
		boolean getPin(){
			return pin;
		}
		
		void pinPage(){
			pin=true;
		}
		
		void unpinPage(){
			pin=false;
		}
		
		void setOwner(VMProcess owner) {
			this.owner=owner;
		}
		void setVPN(int vpn) {
			this.vpn=vpn;
		}
	}
	
	/**
     * Allocate a new VM kernel.
     */
    public VMKernel() {
    	super();
    }

    /**
     * Initialize this kernel.
     * 1) call UserKernel initialize method;
     * 2) Setup an inverted page table for every physical page;
     * 3) Setup pinned bit to be false;
     * 4) Initialize all the locks and condition variables for synchronization .
     */
    public void initialize(String[] args) {
    	super.initialize(args);
    	invPageTable = new invPageEntry[Machine.processor().getNumPhysPages()];
    	IPTLock=new Lock();
    	pinLock=new Lock();
    	swapFile = ThreadedKernel.fileSystem.open("swapFile",true);
    	freeSwapList = new LinkedList<Integer>();
    	swapFileVolume=0;
    	swapLock=new Lock();
    	clockHand=0;
    	clockLock = new Lock();
    	for(int i=0;i<Machine.processor().getNumPhysPages();i++)
    	{
    		invPageTable[i]=new invPageEntry();
    	}
    	/**
    	 * If all physical pages are pinned,
    	 * the process that needs a page would be stalled until a page is unpinned
    	 */
    	allPinned = new Condition(pinLock);
    }

    /**
     * Test this kernel.
     */	
    public void selfTest() {
    	super.selfTest();
    }

    /**
     * Start running user programs.
     */
    public void run() {
    	super.run();
    }
    
    /**
     * Terminate this kernel. Never returns.
     */
    public void terminate() {
    	swapFile.close();
    	super.terminate();
    }

    /**
     * update the page table before swapping pages on to disk
     * @param currentProcess reference to the current process
     * on whom will the updating performed
     */
    public static void updatePageTable(VMProcess currentProcess) {
    	Lib.debug(dbgVM, "\tUpdate Page Table\n");
    	TranslationEntry [] currentPageTable=currentProcess.getPageTable();
    	TranslationEntry TLBtemp;
    	for (int i=0;i<Machine.processor().getTLBSize();i++)
    	{
    		
    		TLBtemp = Machine.processor().readTLBEntry(i);
    		if(TLBtemp.valid)
    		{
    			if (TLBtemp.dirty)
    				currentPageTable[TLBtemp.vpn].dirty=true;
    			if(TLBtemp.used)
    				currentPageTable[TLBtemp.vpn].used=true;
    		}
    	}
    }
    
    /**
     * update the TLB after swapping pages on to disk
     * @param currentProcess reference to the current process
     */
    public static void updateTLB(VMProcess currentProcess) {
    	Lib.debug(dbgVM, "\tUpdate TLB\n");
    	TranslationEntry [] currentPageTable = currentProcess.getPageTable();
    	TranslationEntry TLBtemp;
    	for (int i=0;i<Machine.processor().getTLBSize();i++)
    	{
    		TLBtemp = Machine.processor().readTLBEntry(i);
    		if(TLBtemp.valid)
    		{
    			Machine.processor().writeTLBEntry(i, currentPageTable[TLBtemp.vpn]);
    		}
    	}
    }
    
    /**
     * Find the next page that is not pinned.
     * If all the pages are pinned, the process will be blocked until a pin is released
     * @param clockHand the current clock hand position
     * @return the next clock hand position that is not pinned
     */
    private static int nextNotPinned(int clockHand) {
    	clockHand=(clockHand+1)%Machine.processor().getNumPhysPages();
    	int start=clockHand;
    	pinLock.acquire();
    	while (invPageTable[clockHand].getPin())
    	{
        	clockHand=(clockHand+1)%Machine.processor().getNumPhysPages();
        	if(clockHand==start)
        		allPinned.sleep();
    	}
    	pinLock.release();
    	return clockHand;

    }
    
    /**
     * get the next available page to evict.
     * If the page is dirty, it would write to the swap
     * @return the evicted physical page number
     */
    public static int clock(VMProcess currentProcess) { 
    	Lib.debug(dbgVM,"clock algorithm invoked!\n");
    	int ppn;
    	updatePageTable(currentProcess);
		clockLock.acquire();
    	while(true)
    	{
    		clockHand=nextNotPinned(clockHand);
    		VMProcess process = invPageTable[clockHand].getOwner();
    		int vpn = invPageTable[clockHand].getVPN();
    		TranslationEntry [] pageTable =process.getPageTable();
    		if (pageTable[vpn].used)
    			pageTable[vpn].used=false;
    		else if (pageTable[vpn].dirty)
    		{
    			//need to write to swap file
    			int success=writeToSwap(clockHand);
    			if (success==-1)
    			{
    				clockLock.release();
    				Lib.debug(dbgVM, "clock failed\n");
    				return -1;
    			}
    			pageTable[vpn].valid=false;
    			break;
    		}
    		else
    		{
    			pageTable[vpn].valid=false;
    			break;
    		}
    	}
    	updateTLB(currentProcess);
		ppn=clockHand;
    	clockLock.release();
    	Lib.debug(dbgVM,"\tclock success, pick " +ppn+" page to be evicted\n");
    	return ppn;		
    }
    
    /**
     * get an available swap position.
     * This will first check the free swap position list;
     * if the list is empty, it will then add a new position for swap
     * @return the swap position number
     */
    public static int getSwapPos(){
    	Lib.debug(dbgVM, "\tGet a swap position\n");
    	swapLock.acquire();
    	if (freeSwapList.isEmpty())
    	{
    		int swapPos=swapFileVolume++;
    		Lib.debug(dbgVM, "\tincrease the swap file volume to "+ swapPos+"\n");
    		swapLock.release();
    		return swapPos;
    	}
    	else
    	{
    		int swapPos=freeSwapList.pop();
    		Lib.debug(dbgVM,"\tReuse a swap position "+swapPos+"\n");
    		swapLock.release();
    		return swapPos;
    	}
    }
    
    /**
     * release the swap position, add it up to the free swap position list
     * @param pos the released swap position
     */
    public static void releaseSwapPos(int pos) {
    	swapLock.acquire();
    	Lib.debug(dbgVM,"\trelease swap position "+pos+"\n");
    	freeSwapList.add(pos);
    	swapLock.release();
    }
    
    /**
     * write the content of a physical page to the swap file
     * @param ppn the physical page number
     * @return -1 if write failed, 0 if succeeded
     */
    public static int writeToSwap (int ppn) {
    	Lib.debug(dbgVM,"\tWrite to swapfile\n");
    	int bytesRead;
    	int bytesWritten;

    	int vpn=invPageTable[ppn].getVPN();
    	VMProcess process=invPageTable[ppn].getOwner();

    	int [] swapPositionList=process.getSwapList();
    	byte [] buffer=new byte[pageSize];
    	bytesRead=process.readVirtualMemory(Processor.makeAddress(vpn, 0),buffer,0,pageSize);
    	if (bytesRead!=pageSize)
    	{
    		Lib.debug(dbgVM,"\twrite to swap failed!\n");
    		return -1;
    	}
    	int pos=getSwapPos();
    	swapFile.seek(pos*pageSize);
    	bytesWritten=swapFile.write(buffer, 0, pageSize);
    	if(bytesWritten!=bytesRead)
    	{
    		Lib.debug(dbgVM,"\twrite to swap failed!\n");
    		releaseSwapPos(pos);
    		return -1;
    	}
    	swapPositionList[vpn]=pos;
		Lib.debug(dbgVM,"\twrite to swap succeeded! Swap position: "+pos+"," +
				" Write to vpn:"+vpn+"\n");
    	return 0;
    }
    
    /**
     * read from the swap file into the physical page
     * @param ppn physical page number
     * @param vpn virtual page number
     * @param process reference to the calling process
     * @return -1 on failure, 0 on success
     */
    public static int readFromSwap(int ppn,int vpn, VMProcess process) {
    	Lib.debug(dbgVM,"\tRead from swapfile\n");
    	int bytesWritten;
    	int bytesRead;
    	int [] swapPositionList=process.getSwapList();
    	byte [] buffer=new byte[pageSize];
    	int pos=swapPositionList[vpn];
    	TranslationEntry [] pageTable=process.getPageTable();
    	swapFile.seek(pos*pageSize);
    	bytesRead=swapFile.read(buffer, 0, pageSize);
    	if(bytesRead!=pageSize)
    	{
    		Lib.debug(dbgVM,"\tRead from swap failed!\n");
    		return -1;
    	}
    	bytesWritten=process.writeVirtualMemory(Processor.makeAddress(vpn,0), buffer,0,pageSize);
    	if(bytesWritten!=bytesRead)
    	{
    		Lib.debug(dbgVM,"\tRead from swap failed!\n");
    		return -1;
    	}
    	swapPositionList[vpn]=-1;
    	releaseSwapPos(pos);
    	pageTable[vpn].dirty=true;
    	IPTLock.acquire();
    	invPageTable[ppn].setOwner(process);
		invPageTable[ppn].setVPN(vpn);
		IPTLock.release();
		Lib.debug(dbgVM,"\tRead from swap secceeded!\n");
    	return 0;
    }
    
    
    public static invPageEntry [] invPageTable;
    public static OpenFile swapFile;
    public static LinkedList<Integer> freeSwapList;
    public static int swapFileVolume;
    public static Lock swapLock;
    public static Lock IPTLock;
    public static Lock pinLock;
    public static int clockHand;
    public static Lock clockLock;
    public static Condition allPinned;
    
    private static final int pageSize = Processor.pageSize;
    // dummy variables to make javac smarter
    private static VMProcess dummy1 = null;

    private static final char dbgVM = 'v';
}
