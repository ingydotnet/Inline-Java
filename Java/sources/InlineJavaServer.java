package org.perl.inline.java ;

import java.net.* ;
import java.io.* ;
import java.util.* ;


/*
	This is the server that will answer all the requests for and on Java
	objects.
*/
public class InlineJavaServer {
	private static InlineJavaServer instance = null ;
	private int port = 0 ;
	private boolean shared_jvm = false ;

	private InlineJavaUserClassLoader ijucl = null ;
	private HashMap thread_objects = new HashMap() ;
	private int objid = 1 ;
	private boolean jni = false ;
	private Thread creator = null ;


	// This constructor is used in JNI mode
	private InlineJavaServer(int d){
		init(d) ;

		jni = true ; 
		thread_objects.put(creator, new HashMap()) ;
	}


	// This constructor is used in server mode
	private InlineJavaServer(String[] argv){
		init(new Integer(argv[0]).intValue()) ;

		jni = false ;
		port = Integer.parseInt(argv[1]) ;
		shared_jvm = new Boolean(argv[2]).booleanValue() ;

		ServerSocket ss = null ;
		try {
			ss = new ServerSocket(port) ;	
		}
		catch (IOException e){
			InlineJavaUtils.Fatal("Can't open server socket on port " + String.valueOf(port) +
				": " + e.getMessage()) ;
		}

		while (true){
			try {
				// For now we pass our own InlineJavaUserClassLoader, but later
				//  we can implement privacy by creating a new one.
				InlineJavaServerThread ijt = new InlineJavaServerThread(this, ss.accept(), ijucl) ;
				ijt.start() ;
				if (! shared_jvm){
					try {
						ijt.join() ; 
					}
					catch (InterruptedException e){
					}
					break ;
				}
			}
			catch (IOException e){
				System.err.println("IO error: " + e.getMessage()) ;
				System.err.flush() ;
			}
		}

		System.exit(1) ;
	}


	private void init(int debug){
		instance = this ;
		creator = Thread.currentThread() ;
		InlineJavaUtils.debug = debug ;

		ijucl = new InlineJavaUserClassLoader() ;
	}

	
	static InlineJavaServer GetInstance(){
		if (instance == null){
			InlineJavaUtils.Fatal("No instance of InlineJavaServer has been created!") ;
		}

		return instance ;
	}


	InlineJavaUserClassLoader GetUserClassLoader(){
		Thread t = Thread.currentThread() ;
		if (t instanceof InlineJavaServerThread){
			return ((InlineJavaServerThread)t).GetUserClassLoader() ;
		}
		else{
			return ijucl ;
		}
	}


	String GetType(){
		return (shared_jvm ? "shared" : "private") ;
	}


	/*
		Since this function is also called from the JNI XS extension,
		it's best if it doesn't throw any exceptions.
		It is public only for testing purposes.
	*/
	String ProcessCommand(String cmd) {
		return ProcessCommand(cmd, true) ;
	}


	private String ProcessCommand(String cmd, boolean addlf) {
		InlineJavaUtils.debug(3, "packet recv is " + cmd) ;

		String resp = null ;
		if (cmd != null){
			InlineJavaProtocol ijp = new InlineJavaProtocol(this, cmd) ;
			try {
				ijp.Do() ;
				InlineJavaUtils.debug(3, "packet sent is " + ijp.GetResponse()) ;
				resp = ijp.GetResponse() ;
			}
			catch (InlineJavaException e){
				String err = "error scalar:" + ijp.Encode(e.getMessage()) ;
				InlineJavaUtils.debug(3, "packet sent is " + err) ;
				resp = err ;
			}
		}
		else{
			if (! shared_jvm){
				// Probably connection dropped...
				InlineJavaUtils.debug(1, "lost connection with client in single client mode. Exiting.") ;
				System.exit(1) ;
			}
			else{
				InlineJavaUtils.debug(1, "lost connection with client in shared JVM mode.") ;
				return null ;
			}
		}

		if (addlf){
			resp = resp + "\n" ;
		}

		return resp ;
	}


	native private String jni_callback(String cmd) ;


	// So far this is the only method that can be called (indirectly) by the
	// user code to get back in to Perl. This means that this method really
	// can be called by other threads the are not InlineJavaServerThreads...
	//
	// Is there a way to figure out which InlineJavaServerThread has created
	// the current thread or is logically attached to it?
	Object Callback(String pkg, String method, Object args[], String cast) throws InlineJavaException, InlineJavaPerlException {
		Object ret = null ;

		try {
			InlineJavaProtocol ijp = new InlineJavaProtocol(this, null) ;
			InlineJavaClass ijc = new InlineJavaClass(this, ijp) ;
			StringBuffer cmdb = new StringBuffer("callback " + pkg + " " + method + " " + cast) ;
			if (args != null){
				for (int i = 0 ; i < args.length ; i++){
					 cmdb.append(" " + ijp.SerializeObject(args[i])) ;
				}
			}
			String cmd = cmdb.toString() ;
			InlineJavaUtils.debug(2, "callback command: " + cmd) ;

			Thread t = Thread.currentThread() ;
			String resp = null ;
			while (true) {
				InlineJavaUtils.debug(3, "packet sent (callback) is " + cmd) ;
				if (! jni){
					// Client-server mode
					InlineJavaServerThread ijt = (InlineJavaServerThread)t ;
					ijt.GetWriter().write(cmd + "\n") ;
					ijt.GetWriter().flush() ;

					resp = ijt.GetReader().readLine() ;
				}
				else{
					// JNI mode
					resp = jni_callback(cmd) ;
				}
				InlineJavaUtils.debug(3, "packet recv (callback) is " + resp) ;

				StringTokenizer st = new StringTokenizer(resp, " ") ;
				String c = st.nextToken() ;
				if (c.equals("callback")){
					boolean thrown = new Boolean(st.nextToken()).booleanValue() ;
					String arg = st.nextToken() ;
					ret = ijc.CastArgument(java.lang.Object.class, arg) ;

					if (thrown){
						throw new InlineJavaPerlException(ret) ;
					}

					break ;
				}
				else{
					// Pass it on through the regular channel...
					InlineJavaUtils.debug(3, "packet is not callback response: " + resp) ;
					cmd = ProcessCommand(resp, false) ;

					continue ;
				}
			}
		}
		catch (IOException e){
			throw new InlineJavaException("IO error: " + e.getMessage()) ;
		}

		return ret ;
	}


	boolean IsThreadPerlContact(Thread t){
		if (((jni)&&(t == creator))||
			((! jni)&&(t instanceof InlineJavaServerThread))){
			return true ;
		}

		return false ;
	}


	Object GetObject(int id) throws InlineJavaException {
		Object o = null ;
		HashMap h = (HashMap)thread_objects.get(Thread.currentThread()) ;

		if (h == null){
			throw new InlineJavaException("Can't find thread " + Thread.currentThread().getName() + "!") ;
		}
		else{
			o = h.get(new Integer(id)) ;
			if (o == null){
				throw new InlineJavaException("Can't find object " + id + " for thread " +Thread.currentThread().getName()) ;
			}
		}

		return o ;
	}


	int PutObject(Object o) throws InlineJavaException {
		HashMap h = (HashMap)thread_objects.get(Thread.currentThread()) ;

		int id = objid ;
		if (h == null){
			throw new InlineJavaException("Can't find thread " + Thread.currentThread().getName() + "!") ;
		}
		else{
			h.put(new Integer(objid), o) ;
			objid++ ;
		}

		return id ;
	}


	Object DeleteObject(int id) throws InlineJavaException {
		Object o = null ;
		HashMap h = (HashMap)thread_objects.get(Thread.currentThread()) ;

		if (h == null){
			throw new InlineJavaException("Can't find thread " + Thread.currentThread().getName() + "!") ;
		}
		else{
			o = h.remove(new Integer(id)) ;
			if (o == null){
				throw new InlineJavaException("Can't find object " + id + " for thread " + Thread.currentThread().getName()) ;
			}
		}

		return o ;
	}


	int ObjectCount() throws InlineJavaException {
		int i = -1 ;
		HashMap h = (HashMap)thread_objects.get(Thread.currentThread()) ;

		if (h == null){
			throw new InlineJavaException("Can't find thread " + Thread.currentThread().getName() + "!") ;
		}
		else{
			i = h.values().size() ;
		}

		return i ;
	}


	void AddThread(InlineJavaServerThread t){
		thread_objects.put(t, new HashMap()) ;
	}


	void RemoveThread(InlineJavaServerThread t){
		thread_objects.remove(t) ;
	}
	

	/*
		Startup
	*/
	public static void main(String[] argv){
		new InlineJavaServer(argv) ;
	}


	public static InlineJavaServer jni_main(int debug){
		return new InlineJavaServer(debug) ;
	}
}
