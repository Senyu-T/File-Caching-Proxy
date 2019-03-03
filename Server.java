/* Name: Senyu Tong
 * id: senyut
 */
import java.io.RandomAccessFile;
import java.io.IOException;
import java.io.FileNotFoundException;
import java.io.File;

import java.util.concurrent.ConcurrentHashMap;

import java.rmi.registry.*;
import java.rmi.server.UnicastRemoteObject;
import java.rmi.Naming;
import java.rmi.RemoteException;

import java.nio.file.Paths;
import java.nio.file.Path;
import java.nio.file.Files;

public class Server extends UnicastRemoteObject implements RemoteCall {
    private static String root;
    private static int port;
    // max chunk size for data transfering
    public static final int busSize = 1024 * 1024;

    public Server(String[] args) throws RemoteException {
        Server.port = Integer.parseInt(args[0]);
        File f = new File(args[1]);
        Server.root = null;
        try {
            Server.root = f.getCanonicalPath();
        } catch (IOException e) {
            e.printStackTrace();
        }
        System.err.println("Server with root: " + this.root);
    }

    /* Get the file path under serverDir */
    public String getServerPath(String path) {
        String sPath = root;
        if (root.endsWith("/"))
            sPath += path;
        else
            sPath += "/" + path;
        System.err.println("Server path for file: " + sPath);
        return sPath;
    }

    /* Get File Version, reply in a FileInfo struct, only care about
     * errno (FileNotFound), versionNumber, and size.
     */
    public FileInfo getVersion(String path, FileHandling.OpenOption o)
        throws RemoteException {
        String sPath = getServerPath(path);
        FileInfo reply = null;
        String sAbs = null;
        File s = new File(sPath);
        reply = new FileInfo(s);
        reply.path = path;
        reply.sPath = sPath;

        // Check if the path is in the server root dir
        try {
            sAbs = s.getCanonicalPath();
            if (!sAbs.startsWith(root)) {
                System.err.println(sAbs);
                System.err.println("File: " + sPath + " not found.");
                // pack FileNotFound info into reply
                reply = new FileInfo(FileHandling.Errors.EPERM);
                return reply;
            }
        } catch (IOException e) {
            System.err.println("getversion error");
            e.printStackTrace();
        }


        /* Check Errono */
        /* Check exists */
        if (!s.exists()) {
            if (o != FileHandling.OpenOption.CREATE
             && o != FileHandling.OpenOption.CREATE_NEW) {
                reply.errno = FileHandling.Errors.ENOENT;
                System.err.println("R/W on not exists file");
                return reply;
            } else {
                /* the operation call on create, so we create a file */
                try {
                    s.createNewFile();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        } else {
            if (o == FileHandling.OpenOption.CREATE_NEW) {
                reply.errno = FileHandling.Errors.EEXIST;
                System.err.println("Creating an already existed file");
                return reply;
            }
        }

        /* Check Dir */
        if (s.isDirectory()) {
            if (o != FileHandling.OpenOption.READ) {
                System.err.println("Can't open dir, not read");
                reply.errno = FileHandling.Errors.EISDIR;
                return reply;
            }
        }

        /* Check for permissions */
        if (!s.canRead()) {
            if (o == FileHandling.OpenOption.READ ||
                    o == FileHandling.OpenOption.WRITE ||
                    o == FileHandling.OpenOption.CREATE) {
                System.err.println(" can't read, permission denied ");
                reply.errno = FileHandling.Errors.EPERM;
                return reply;
            }
        }
        if (!s.canWrite()) {
            if (o == FileHandling.OpenOption.WRITE ||
                    o == FileHandling.OpenOption.CREATE) {
                System.err.println("Can't write. permission denied ");
                reply.errno = FileHandling.Errors.EPERM;
                return reply;
            }
        }

        reply.path = path;
        reply.sPath = sPath;
        return reply;
    }

    /* read a file and put info on the bus, send to proxy */
    public Bus sendToProxy(FileInfo fi, long offset) throws RemoteException {
        assert(!fi.isDir);
        String path = fi.path;
        String sPath = fi.sPath;
        File file = new File(sPath);
        RandomAccessFile bF  = null;
        int readCount = 0;
        try {
            bF = new RandomAccessFile(file, "r");
        } catch (FileNotFoundException e) {
            System.err.println("downlaoding file not found on server");
            e.printStackTrace();
        }

        readCount = (int)file.length() - (int)offset;
        if (readCount > busSize) readCount = busSize;
        Bus bus = new Bus(readCount);

        try {
            bF.seek(offset);
            readCount = bF.read(bus.buffer, 0, readCount);
            if (readCount == -1) readCount = 0;
            bF.close();
        } catch (IOException e) {
            e.printStackTrace();
        }

        if (readCount == 0)
            bus.size = bus.buffer.length;
        else
            bus.size = readCount;

        return bus;
    }

    /* getting write update from the proxy */
    public void updateFromProxy(long offset, Bus bus,String path) throws RemoteException {
        String sPath = getServerPath(path);
        System.err.println("updating to");
        System.err.println(sPath);
        File f = new File(sPath);
        try {
            RandomAccessFile rF = new RandomAccessFile(f, "rw");
            rF.seek(offset);
            rF.write(bus.buffer, 0, bus.size);
            rF.close();
        } catch (IOException e) {
            System.err.println("error happens updating file from proxy");
            e.printStackTrace();
        }
    }

    public synchronized int unlinkFile(String path) {
        System.err.println("unlinking server" + path);
        String sPath = getServerPath(path);
        File f = new File(sPath);
        if (!f.exists()) {
            return FileHandling.Errors.ENOENT;
        }
        if (f.isDirectory()) {
            return FileHandling.Errors.EISDIR;
        }
        if (!f.canWrite()) {
            return FileHandling.Errors.EPERM;
        }

        f.delete();
        return 0;
    }

    public static void main(String[] args) {
        assert(args.length == 2);
        try {
            Server server = new Server(args);
            LocateRegistry.createRegistry(server.port);
            Naming.rebind("//127.0.0.1:" + args[0] + "/Server", server);
        } catch (Exception e) {
            e.printStackTrace();
        }
        System.err.println("Server ok");
    }

}
