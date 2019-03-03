/* Name: Senyu Tong
 * id: senyut
 * Proxy.java: emulate C library IO calls,
 *             check-on-use and LRU cache.
 */

import java.io.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.net.MalformedURLException;
import java.rmi.NotBoundException;
import java.rmi.Naming;
import java.rmi.RemoteException;
import java.nio.file.Paths;
import java.nio.file.Path;
import java.nio.file.Files;


class Proxy {

    // the generated file descriptor, 65 for differentiataing local
    private static AtomicInteger fD = new AtomicInteger(65);

    /* From cmd arguments */
    private static String serverIp;
    private static String serverPort;
    private static String cacheRoot;
    private static long cacheSize;
    private static final ReentrantLock lock = new ReentrantLock();

    /* Server */
    private static RemoteCall server;

    /* the cache */
    private static Cache cache;

    /* Proxy Constructor, initialize cache and connect to the server. */
    public Proxy(String[] args) {
        this.serverIp = args[0];
        this.serverPort = args[1];
        String cmdPath = args[2];
        if (cmdPath.charAt(0) == '/')
            cmdPath = cmdPath.substring(1,cmdPath.length());
        File f = new File(cmdPath);
        try {
            if (!f.exists()) {
                f.mkdirs();
            }
            if (!f.isDirectory()) {
                System.err.println("WRONG CACHE DIR");
            }
            this.cacheRoot = f.getCanonicalPath();
        } catch (IOException e) {
            e.printStackTrace();
        }
        this.cacheSize = Long.parseLong(args[3]);
        this.server = connect(this.serverIp, this.serverPort);
        this.cache = new Cache(cacheSize);
    }

    /* connet: connect to the server */
    public static RemoteCall connect(String ip, String port) {
        String url = "//" + ip + ":" + port + "/Server";
        try {
            return (RemoteCall) Naming.lookup(url);
        } catch(RemoteException e) {
            System.err.println("Remote exception in building connection");
        } catch (MalformedURLException e) {
            System.err.println("url malformed");
        } catch (NotBoundException e) {
            System.err.println("connection not bound");
        }
        System.err.println("Server Failed\n");
        return null;
    }


    /* File Handler */
	private static class FileHandler implements FileHandling {
        /* Current opening files on this proxy */
        private ConcurrentHashMap<Integer, MyFile> fdTable;
        /* Current opening directories on this proxy */
        private CopyOnWriteArrayList<Integer> dirArray;

        public FileHandler() {
            fdTable = new ConcurrentHashMap<Integer, MyFile>();
            dirArray = new CopyOnWriteArrayList<Integer>();
        }

        /* getCachedPath: given a file path, find the file in cachedir */
        public String getCachedPath(String path) {
            String cachePath = cacheRoot;
            if (cacheRoot.endsWith("/"))
                cachePath += path;
            else
                cachePath += "/" + path;
            return cachePath;
        }


        /* downloadFile: given FileInfo fi, the metainfo from server,
         *              download the file from the server */
        public synchronized MyFile downloadFile(FileInfo fi, MyFile orig) {
            long offset = 0;
            long readByte = 0;
            /* store the downloaded file in the cache list */
            RandomAccessFile f = null;
            String cacheStore = orig.cachePath;
            try {
                f = new RandomAccessFile(cacheStore, "rw");
                long total = 0;
                while (offset < fi.size) {
                    // construct a bus for fie transfering
                    Bus bus = server.sendToProxy(fi, offset);
                    if (bus.size == 0) break;
                    offset += bus.size;
                    f.write(bus.buffer, 0, bus.size);
                    f.seek(offset);
                }
                f.close();
            } catch (Exception e) {
                System.err.println("downloading fail");
                e.printStackTrace();
            }
            MyFile mf = null;
            try {
                mf = new MyFile(fi, f, orig);
            } catch (Exception e) {
                System.err.println("downloading fail for setting myF");
            }
            System.err.println("we download: " + fi.path);
            return mf;
        }

        /* get the path for copies */
        public String getReadCopy(String path, long ver) {
            return path + "_read_" + Long.toString(ver);
        }
        public String getWriteCopy(String path, int fd) {
            return path + "_write_" + Integer.toString(fd);
        }

        /* transDir: flatten all the subdirs by changing / to _ */
        public String transDir(String path) {
            return path.replace('/', '_');
        }


        /* create a copy for read */
        public String createReadCopy(String path, long ver) {
            String readName = getReadCopy(path, ver);
            String readCache = getCachedPath(readName);
            try {
                Files.copy(Paths.get(getCachedPath(path)), Paths.get(readCache));
            } catch (Exception e) {
                System.err.println("Crashing in creating copy");
                e.printStackTrace();
            }
            System.err.println("Create Read copy in " + readName);
            return readName;
        }

        /* create a copy for write */
        public String createWriteCopy(String path, int fd) {
            String writeName = getWriteCopy(path, fd);
            String writeCache = getCachedPath(writeName);
            try {
                Files.copy(Paths.get(getCachedPath(path)), Paths.get(writeCache));
            } catch (Exception e) {
                System.err.println("Crashign in creating write copy");
                e.printStackTrace();
            }
            return writeName;
        }


        /* open: open a file, first check the version by calling the server,
         * if miss, then download the entire file and put it into the chache,
         * otherwise just operate on the cached file. */
		public int open( String path, OpenOption o ) {
            // lock for entering critical region
            lock.lock();
            FileInfo fInfo;   // server file info
            try {
                fInfo = server.getVersion(path, o);
            } catch (RemoteException e) {
                e.printStackTrace();
                lock.unlock();
                return Errors.EBUSY;
            }

            /* check for errno */
            if (fInfo.errno != 0) {
                System.err.println("Error in opening");
                lock.unlock();
                return fInfo.errno;
            }

            /* check if cache and server has same version
             * if not, download from server */
            String origPath = path;
            path = transDir(path);
            MyFile origF = cache.lookUp(path);
            MyFile myF = null;
            long cacheVer = cache.findVer(path);
            /* cold miss */
            System.err.println("");
            if (cacheVer == -1) {
                System.err.println("cold miss");
                if (!cache.hasSpace(fInfo.size)) {
                    if (!cache.makeRoom(fInfo.size)) {
                        lock.unlock();
                        return Errors.ENOMEM;
                    }
                }
                assert(origF == null);
                origF = new MyFile(path, getCachedPath(path), origPath);
                myF = downloadFile(fInfo, origF);
                cache.push(myF);
                cache.add(myF);
            }
            else if (fInfo.version != cacheVer) {
                System.err.println("outofdate miss");
                /* firstly we evict the old version */
                cache.evict(cache.lookUp(path));
                if (!cache.hasSpace(fInfo.size)) {
                    if (!cache.makeRoom(fInfo.size)) {
                        lock.unlock();
                        return Errors.ENOMEM;
                    }
                }
                myF = downloadFile(fInfo, origF);
                /* update to cache */
                cache.push(myF);
                cache.add(myF);
            }
            /* hit */
            else {
                System.err.println("cachehit");
                myF = origF;
                cache.update(myF);
            }

            /* the actual open operation */
            if (o == OpenOption.READ) {
                    int retFd = fD.getAndIncrement();
                    /* check for directory */
                    if (fInfo.isDir) {
                        dirArray.add(retFd);
                        lock.unlock();
                        return retFd;
                    }
                    /* check if there exists any read copy */
                    if (myF.readerCount == 0) {
                        myF.readerCount += 1;
                        /* we are using more cache space,
                         * check for availability */
                        if (!cache.hasSpace(fInfo.size)) {
                            if (!cache.makeRoom(fInfo.size)) {
                                lock.unlock();
                                return Errors.ENOMEM;
                            }
                        }
                        // create local copy for reading
                        String copyPath = createReadCopy(path, myF.version);
                        String copyCache = getCachedPath(copyPath);
                        MyFile readCopy = new MyFile(myF, copyPath, copyCache,true);
                        readCopy.readOnly = true;

                        fdTable.put(retFd, readCopy);
                        cache.push(readCopy);
                        lock.unlock();
                        return retFd;
                    } else {
                        // if there already exists a read copy
                        myF.readerCount += 1;
                        String copyPath = getReadCopy(path, myF.version);
                        MyFile copy = cache.lookUp(copyPath);
                        System.err.println("opening copy: " + copyPath);
                        copy.readerCount += 1;
                        fdTable.put(retFd, copy);
                        lock.unlock();
                        return retFd;
                    }
            } else {
                if (!cache.hasSpace(fInfo.size)) {
                    if (!cache.makeRoom(fInfo.size)) {
                        lock.unlock();
                        return Errors.ENOMEM;
                    }
                }
                int retFd = fD.getAndIncrement();
                String copyPath = createWriteCopy(path, retFd);
                String copyCache = getCachedPath(copyPath);
                MyFile writeCopy = new MyFile(myF, copyPath, copyCache, false);
                fdTable.put(retFd, writeCopy);
                cache.push(writeCopy);

                System.err.println("write on: " + copyCache);
                lock.unlock();
                return retFd;
            }
		}

        /* uploading a modified file to the server */
        public synchronized void upload(MyFile f, int fd) {
            RandomAccessFile rF = f.rF;
            long offset = 0;
            long totalBytes = 0;
            try {
                totalBytes = rF.length();
            } catch (IOException e) {
                e.printStackTrace();
            }

            while (offset < totalBytes) {
                Bus bus = null;
                try {
                    rF.seek(offset);
                    int writeCount = (int)totalBytes - (int)offset;
                    if (writeCount > Server.busSize) writeCount = Server.busSize;
                    bus = new Bus(writeCount);
                    bus.size = rF.read(bus.buffer, 0, writeCount);
                    if (bus.size == -1) {
                        // EOF
                        bus.size = 0;
                        break;
                    }
                    server.updateFromProxy((int)offset, bus, f.origPath);
                    offset += bus.size;
                } catch (Exception e) {
                    System.err.println("uploading error ");
                    e.printStackTrace();
                }
            }
            try {
                rF.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        // return back the original file name for the master copy
        public String getReadOrig(MyFile f) {
            String p = f.path;
            String suffix = "_read_" + Long.toString(f.version);
            String origName = p.substring(0, p.length() - suffix.length());
            return origName;
        }

        public String getWriteOrig(String path, int fd) {
            String suffix = "_write_" + Integer.toString(fd);
            String uploadPath = path.substring(0, path.length() - suffix.length());
            return uploadPath;
        }

        /* close:
         *   if fd invald, return errno;
         *   if file an read copy, evict from cache if no one else reading it;
         *   if file an write copy, evict and update on server.*/
		public int close( int fd ) {
            lock.lock();
            if (dirArray.contains(fd)) {
                dirArray.remove(new Integer(fd));
                lock.unlock();
                return 0;
            }
            MyFile f = fdTable.get(fd);
            if (f == null) {
                lock.unlock();
                return Errors.EBADF;
            }
            // check if any updated needed
            if (!f.readOnly) {
                try {
                    /* the orig file should be transformed to this write file */
                    upload(f, fd);
                    f.rF.close();
                    String origFileName = getWriteOrig(f.path, fd);
                    System.err.println("get upload " + f.origPath);
                    /* delete the old version in cache,
                     * update it to this write file*/
                    cache.cover(origFileName, f);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            } else {
                String origName = getReadOrig(f);
                System.err.println("Trying to close orig: " + origName);
                MyFile orig = cache.lookUp(origName);
                if (orig != null) {
                    orig.readerCount -= 1;
                    f.readerCount -= 1;
                    if (f.readerCount == 0) {
                        try {
                            f.rF.close();
                            cache.evict(f);
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                    cache.update(orig);
                }
                else {
                    /* if we have deleted the original master file
                     * but by close semantics this is the latest
                     * we push it back to the cache */
                    f.readerCount -= 1;
                    String masterPath = origName;
                    String masterCache = getCachedPath(origName);
                    MyFile newMaster = null;
                    try {
                        Files.move(Paths.get(f.cachePath), Paths.get(masterCache));
                        newMaster = new MyFile(f, masterPath, masterCache, true);
                        f.rF.close();
                        if (f.readerCount == 0) {
                            cache.evict(f);
                        }
                        if (!cache.hasSpace(newMaster.fileSize)) {
                            boolean flag = cache.makeRoom(newMaster.fileSize);
                        }
                        cache.push(newMaster);
                        cache.add(newMaster);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
            fdTable.remove(fd);
            lock.unlock();
            return 0;
		}

        /* write: function for writing file
         *   Given file descriptor, find file from
         *   the file map, and perform normal operation
         *   on the randomFile
         */
		public long write( int fd, byte[] buf ) {
            if (dirArray.contains(fd)) {
                return Errors.EISDIR;
            }
            MyFile f = fdTable.get(fd);
            if (f == null) {
                return Errors.EBADF;
            }
            if (f.readOnly) {
                return Errors.EBADF;
            }
            try {
                f.rF.write(buf);
            } catch (IOException e) {
                e.printStackTrace();
                return Errors.EBUSY;
            }
            return buf.length;
		}

        /* read: normal read operation */
		public long read( int fd, byte[] buf ) {
            if (dirArray.contains(fd)) {
                return Errors.EISDIR;
            }
            MyFile f = fdTable.get(fd);
            if (f == null) {
                return Errors.EBADF;
            }
            if (buf == null) {
                System.err.println("buf null error \n");
                return Errors.EINVAL;
            }
            long readLen = buf.length;
            try {
                readLen = f.rF.read(buf);
                // EOF
                if (readLen == -1) {
                    return 0;
                }
            } catch (IOException e) {
                e.printStackTrace();
                return Errors.EBUSY;
            }
            return readLen;
		}

        /* lseek: normal lseek operation */
		public long lseek( int fd, long pos, LseekOption o ) {
            if (dirArray.contains(fd)) {
                return Errors.EISDIR;
            }
            // get the file
            MyFile f = fdTable.get(fd);
            if (f == null) {
                return Errors.EBADF;
            }
            /* switch for option, for where to start */
            switch (o) {
                case FROM_CURRENT:
                    try {
                        long offset = f.rF.getFilePointer() + pos;
                        f.rF.seek(offset);
                    } catch (IOException e) {
                        e.printStackTrace();
                        return Errors.EBUSY;
                    }
                    break;
                case FROM_START:
                    try {
                        f.rF.seek(pos);
                    } catch (IOException e) {
                        e.printStackTrace();
                        return Errors.EBUSY;
                    }
                    break;
                case FROM_END:
                    try {
                        long offset = f.rF.length() + pos;
                        f.rF.seek(offset);
                    } catch (IOException e) {
                        e.printStackTrace();
                        return Errors.EBUSY;
                    }
                    break;
                default:
                    return Errors.EINVAL;
            }
            try {
                long p = f.rF.getFilePointer();
                return p;
            } catch (Exception e) {
                e.printStackTrace();
                return Errors.EBUSY;
            }
		}

        /* unlink: perform operation on randomFile instance */
		public int unlink( String path ) {
            System.err.println("Unlinking " + path);
            int r = 0;
            try {
                /* unlink server files regardless of there is local copy.*/
                r = server.unlinkFile(path);
            } catch (Exception e) {
                e.printStackTrace();
                return Errors.EBUSY;
            }
            return r;
		}

        /* clientdone: empty the hashmaps */
		public void clientdone() {
            for (MyFile f: fdTable.values()) {
                try {
                    f.rF.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            fdTable.clear();
            return;
		}

	}

	private static class FileHandlingFactory implements FileHandlingMaking {
		public FileHandling newclient() {
			return new FileHandler();
		}
	}

	public static void main(String[] args) throws IOException {
        Proxy proxy = new Proxy(args);
		(new RPCreceiver(new FileHandlingFactory())).run();
	}
}

