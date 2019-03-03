import java.io.RandomAccessFile;
import java.io.*;

public class MyFile {
    public String path;
    public String origPath;
    public String cachePath;

    public long fileSize;
    public long version;
    public boolean readOnly;   // Read-Only ?
    public int readerCount;
    public RandomAccessFile rF;

    public MyFile(FileInfo fi, RandomAccessFile rF, MyFile f) {
        this.path = f.path;
        this.cachePath = f.cachePath;
        this.version = fi.version;
        this.fileSize = fi.size;
        this.readOnly = (fi.canRead && !fi.canWrite);
        this.readerCount = 0;
        this.origPath = f.origPath;
        this.rF = rF;
    }

    public MyFile(String path, String cachePath, String origPath) {
        this.path = path;
        this.cachePath = cachePath;
        this.readerCount = 0;
        this.rF = null;
        this.origPath = origPath;
    }

    public MyFile(MyFile f, String copyPath, String cacheP, boolean readOnly) {
        this.path = copyPath;
        this.cachePath = cacheP;
        this.fileSize = f.fileSize;
        this.origPath = f.origPath;
        this.version = f.version;
        this.readOnly = readOnly;
        this.readerCount = f.readerCount;
        /* create explicit copy for randomfile ? */
        this.rF = null;
        try {
            this.rF = new RandomAccessFile((new File(cacheP)), "rw");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
