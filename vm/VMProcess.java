package nachos.vm;

import nachos.machine.*;
import nachos.threads.*;
import nachos.userprog.*;
import nachos.vm.*;

/**
 * A <tt>UserProcess</tt> that supports demand-paging.
 */
public class VMProcess extends UserProcess {
    /**
     * Allocate a new process.
     */
    public VMProcess() {
	super();
    }

    /**
     * Save the state of this process in preparation for a context switch.
     * Called by <tt>UThread.saveState()</tt>.
     */
    public void saveState() {
    	Lib.debug(dbgVM,"\tsave state!\n");
    	flushTLB();
    }

    /**
     * Restore the state of this process after a context switch. Called by
     * <tt>UThread.restoreState()</tt>.
     */
    public void restoreState() {
    	Lib.debug(dbgVM, "\tRestore state!\n");
    }

    /**
     * get the swap list
     * @return swap position list of the process
     */
    public int [] getSwapList(){
    	return swapPositionList;
    }
    
    /**
     * 
     * @return page table of the process
     */
    public TranslationEntry [] getPageTable(){
    	return pageTable;
    }
    
    /**
     * Initializes page tables for this process so that the executable can be
     * demand-paged.
     *
     * @return	<tt>true</tt> if successful.
     */
    protected boolean loadSections() {
    	Lib.debug(dbgVM, "\tLoad sections\n");
    	pageTable=new TranslationEntry[numPages];
    	swapPositionList=new int[numPages];
    	for (int i=0;i<numPages;i++)
    	{
    		pageTable[i]=new TranslationEntry(i,-1,false,false,false,false);
    		swapPositionList[i]=-1;
    	}
    	Lib.debug(dbgVM, "\tTotal virtual pages: "+numPages+"\n");
    	return true;
    }

    /**
     * Release any resources allocated by <tt>loadSections()</tt>.
     */
    protected void unloadSections() {
    	Lib.debug(dbgVM,"\tUnload sections\n");
    	VMKernel.memoryMappingLock.acquire();
    	for (int i=0;i<pageTable.length;i++)
    	{
    		if(pageTable[i].valid)
    		{
    			int ppn=vpnToPpn(i,false);
    			VMKernel.availablePhysicalPages.add(ppn);
    			VMKernel.pinLock.acquire();
    			VMKernel.invPageTable[ppn].unpinPage();
    			VMKernel.pinLock.release();
    			VMKernel.IPTLock.acquire();
    			VMKernel.invPageTable[ppn].setOwner(null);
    			VMKernel.invPageTable[ppn].setVPN(-1);
    			VMKernel.IPTLock.release();		
    		}
    		if(swapPositionList[i]!=-1)
    		{
    			VMKernel.releaseSwapPos(swapPositionList[i]);
    		}
    	}
    	VMKernel.pinLock.acquire();
    	VMKernel.allPinned.wakeAll();
    	VMKernel.pinLock.release();
    	VMKernel.memoryMappingLock.release();
    	flushTLB();
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
    Lib.debug(dbgVM,"readVirtualMemory called\n");
	if(offset < 0 || length < 0 || offset+length > data.length || vaddr<0)
		return 0;

	byte[] memory = Machine.processor().getMemory();
	
	int bytesRead=0;
	//should consider vaddr+offset
	while(length>0)
	{
		int vpn=Processor.pageFromAddress(vaddr);
		int ppn;
		int vOffset=Processor.offsetFromAddress(vaddr);
		if (vpn<0||vpn>=pageTable.length)
			return bytesRead;
		if(!pageTable[vpn].valid)
		{
			ppn=pageFault(vpn);
		}
		else
		{
			ppn=vpnToPpn(vpn,false);
		}
		if(ppn==-1)
			return bytesRead;
		VMKernel.pinLock.acquire();
		VMKernel.invPageTable[ppn].pinPage();
		VMKernel.pinLock.release();
		int  byteInPageRead= Math.min(length, pageSize-vOffset);
		int paddr=ppn*Processor.pageSize+vOffset;
		System.arraycopy(memory, paddr, data, offset, byteInPageRead);
		VMKernel.pinLock.acquire();
		VMKernel.invPageTable[ppn].unpinPage();
		VMKernel.allPinned.wakeAll();
		VMKernel.pinLock.release();
		length=length-byteInPageRead;
		vaddr+=byteInPageRead;
		offset+=byteInPageRead;
		bytesRead+=byteInPageRead;
	}
	Lib.debug(dbgVM, "successfully read from memory\n");
	return bytesRead;
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
    Lib.debug(dbgVM,"writeVirtualMemory called\n");	
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
		int ppn;
		if (vpn<0||vpn>=pageTable.length)
			return bytesWrite;
		if(!pageTable[vpn].valid)
		{
			ppn=pageFault(vpn);
		}
		else
		{
			ppn=vpnToPpn(vpn,true);
		}
		if (ppn==-1)
			return bytesWrite;
		VMKernel.pinLock.acquire();
		VMKernel.invPageTable[ppn].pinPage();
		VMKernel.pinLock.release();
		int  byteInPageWrite= Math.min(length, pageSize-vOffset);
		int paddr=ppn*Processor.pageSize+vOffset;
		System.arraycopy(data,offset,memory,paddr,byteInPageWrite);
		VMKernel.pinLock.acquire();
		VMKernel.invPageTable[ppn].unpinPage();
		VMKernel.allPinned.wakeAll();
		VMKernel.pinLock.release();
		length=length-byteInPageWrite;
		vaddr+=byteInPageWrite;
		offset+=byteInPageWrite;
		bytesWrite+=byteInPageWrite;
	}
	Lib.debug(dbgVM,"successfully write to memory\n");
	return bytesWrite;
    }
    /**
     * Flush all TLB when context switching
     * Write back dirty and used bits if they are modified to true
     * This function is called in saveState
     */
    private void flushTLB() {
    	Lib.debug(dbgVM,"\tTLB flush...\n");
    	for (int i=0;i<Machine.processor().getTLBSize();i++)
    	{
    		TranslationEntry page = Machine.processor().readTLBEntry(i);
    		if(page.valid)
    		{
    			if (page.dirty)
    				pageTable[page.vpn].dirty=true;
    			if(page.used)
    				pageTable[page.vpn].used=true;
    		}
    		page.valid=false;
    		Machine.processor().writeTLBEntry(i, page);
    	}
    	Lib.debug(dbgVM,"\tflushed\n");
    }
    
    
    private void loadOnePage(int vpn, int ppn) {
    	Lib.debug(dbgVM, "\tLoad a page from disk\n");
    	Lib.assertTrue(vpn>=0&&vpn<pageTable.length);
    	VMKernel.pinLock.acquire();
    	VMKernel.invPageTable[ppn].pinPage();
    	VMKernel.pinLock.release();
    	int coffLength=numPages-9;
    	//load from coff file
    	if(vpn<coffLength)
    	{
    		for (int s=0; s<coff.getNumSections(); s++) {
    		    CoffSection section = coff.getSection(s);
    		    
    		    if (vpn-section.getFirstVPN()<section.getLength())
    		    {
    		    	Lib.debug(dbgVM, "\tinitializing vpn: " + vpn +
        		    		", ppn: " + ppn
        			      + "; section "+ section.getName()+" has "+
        		    		section.getLength()+" pages,"
        		    		+" (" + (vpn-section.getFirstVPN()) + " page)\n");
    		    	pageTable[vpn].readOnly=section.isReadOnly();
    		    	if(pageTable[vpn].readOnly)
    		    		Lib.debug(dbgVM,"\tReadOnly coff file\n");
    		    	section.loadPage(vpn-section.getFirstVPN(), ppn);
    		    	break;
    		    }
    		}
    	}
    	else
    		Lib.debug(dbgVM,"\tBlank page loaded\n");
    	pageTable[vpn].ppn=ppn;
    	VMKernel.IPTLock.acquire();
    	VMKernel.invPageTable[ppn].setOwner(this);
    	VMKernel.invPageTable[ppn].setVPN(vpn);
    	VMKernel.IPTLock.release();
    	VMKernel.pinLock.acquire();
    	VMKernel.invPageTable[ppn].unpinPage();
    	VMKernel.pinLock.release();
    	Lib.debug(dbgVM, "\tload page success!\n");
    }
    
    protected int pageFault(int vpn){
    	//first check if vpn is valid
    	Lib.debug(dbgVM,"\tHandling page fault!\n");
    	Lib.assertTrue(!pageTable[vpn].valid);
    	int ppn;
		VMKernel.memoryMappingLock.acquire();
    	//not in the swapFile, should read from the original file
    	if(swapPositionList[vpn]==-1)
    	{
    		Lib.debug(dbgVM, "Not in swap list\n");
    		if (VMKernel.availablePhysicalPages.isEmpty()){
    			Lib.debug(dbgVM, "Not enough free memory\n");
    			ppn=VMKernel.clock(this);
    			if(ppn==-1)
    			{
    				Lib.debug(dbgVM, "\tpage fault handling failed\n");
    				VMKernel.memoryMappingLock.release();
    				return -1;
    			}
    		}
    		else
    		{
    			Lib.debug(dbgVM, "Has free memory page\n");
    			ppn=VMKernel.availablePhysicalPages.removeFirst();
    		}
    		loadOnePage(vpn,ppn);
    	}
    	else
    	{
    		Lib.debug(dbgVM,"In swap list\n");
    		if (VMKernel.availablePhysicalPages.isEmpty()){
				Lib.debug(dbgVM, "\tpage fault handling failed\n");
    			ppn=VMKernel.clock(this);
    			if(ppn==-1)
    			{
    				Lib.debug(dbgVM, "\tpage fault handling failed\n");
    				VMKernel.memoryMappingLock.release();
    				return -1;
    			}
    		}
    		else
    		{
    			Lib.debug(dbgVM, "Has free memory page\n");
    			ppn=VMKernel.availablePhysicalPages.removeFirst();
    		}
    		pageTable[vpn].ppn=ppn;
    		pageTable[vpn].valid=true;
    		int success=VMKernel.readFromSwap(ppn, vpn, this);
    		if (success==-1)
    		{
    			pageTable[vpn].valid=false;
    			Lib.debug(dbgVM, "\tpage fault handling failed\n");
				VMKernel.memoryMappingLock.release();
				return -1;
    		}
    		pageTable[vpn].dirty=true;
    	}
    	pageTable[vpn].used=true;
    	pageTable[vpn].valid=true;
    	VMKernel.memoryMappingLock.release();
    	Lib.debug(dbgVM, "\tpage fault successfully handled\n");
    	return ppn;
    }
    /**
     * Function for handling TLB miss situation
     * @param badVAddr the address whose virtual page number is not in the TLB
     * @return -1 for error, 0 for normal handling
     */
    protected int handleTLBMiss(int badVAddr){
    	int vpn=Processor.pageFromAddress(badVAddr);
    	Lib.debug(dbgVM,"\tVPN "+vpn+" not in TLB, miss handling start...\n");
    	if(vpn<0||vpn>pageTable.length)
    	{
    		Lib.debug(dbgVM,"vpn out of range, failed!\n");
    		return -1;
    	}
    	int ppn;
    	//randomly pick a page to evict
    	int evict = (int)(Math.random()*Machine.processor().getTLBSize());
    	Lib.debug(dbgVM, "\tTLB "+evict +" will be evicted\n");
    	TranslationEntry evictedPage =Machine.processor().readTLBEntry(evict);
    	//write back to page table the bit sign
    	if(evictedPage.valid)
    	{
    		if (evictedPage.dirty)
    			pageTable[evictedPage.vpn].dirty=true;
    		if(evictedPage.used)
    			pageTable[evictedPage.vpn].used=true;
    	}
    	//handle pageFault if there is any
    	if(!pageTable[vpn].valid)
    	{
    		ppn=pageFault(vpn);
    		if (ppn==-1)
    		{
    			Lib.debug(dbgVM,"failed!\n");
    			return -1;
    		}
    	}
    	Machine.processor().writeTLBEntry(evict,pageTable[vpn]);
    	Lib.debug(dbgVM,"succeeded!\n");
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
	case Processor.exceptionTLBMiss:
		handleTLBMiss(processor.readRegister(Processor.regBadVAddr));
		break;
	default:
	    super.handleException(cause);
	    break;
	}
    }
	
    public void printTLB(){
    	Lib.debug(dbgVM, "\tprint out TLB\n");
    	for(int i=0;i<Machine.processor().getTLBSize();i++)
    	{
    		TranslationEntry page=Machine.processor().readTLBEntry(i);
    		Lib.debug(dbgVM,"page VPN: "+page.vpn+", ppn: "+page.ppn+
    				", valid: "+ page.valid+", readOnly: "+page.readOnly+", used: "+
    				page.used+", dirty: "+page.dirty+".\n");
    	}
    }
    
    public void printPageTable(){
    	Lib.debug(dbgVM, "\tprint out page table\n");
    	for(int i=0;i<Machine.processor().getNumPhysPages();i++)
    	{
    		VMProcess process=VMKernel.invPageTable[i].getOwner();
    		int vpn=VMKernel.invPageTable[i].getVPN();
    		if(vpn!=-1)
    		{
    			TranslationEntry [] PT=process.getPageTable();
    			Lib.debug(dbgVM,"page VPN: "+PT[vpn].vpn+", ppn: "+PT[vpn].ppn+
    					", valid: "+ PT[vpn].valid+", readOnly: "+PT[vpn].readOnly+", used: "+
    					PT[vpn].used+", dirty: "+PT[vpn].dirty+".\n");
    		}
    	}
    }
    
    private int [] swapPositionList;
    private static final int pageSize = Processor.pageSize;
    private static final char dbgProcess = 'a';
    private static final char dbgVM = 'v';
}
