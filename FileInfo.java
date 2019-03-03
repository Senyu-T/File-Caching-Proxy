/* FileInfo: for information transferring between server and proxy
 *           the meta date for files
 */
import java.io.File;

public class FileInfo implements java.io.Serializable {
    public long size;
    public long version;
    public int errno;
    public boolean isDir;
    public boolean exist;
    public boolean canRead;
    public boolean canWrite;
    public String sPath;
    public String path;

    public FileInfo() {
        this.size = 0;
        this.version = 0;
        this.errno = 0;
        this.isDir = false;
        this.exist = true;
        this.canRead = true;
        this.canWrite = true;
    }

    public FileInfo(int err) {
        this.size = -1;
        this.errno = err;
    }

    public FileInfo(File f) {
        this.version = f.lastModified();
        this.size = f.length();
        this.exist = f.exists();
        this.isDir = f.isDirectory();
        this.canRead = f.canRead();
        this.canWrite = f.canWrite();
    }

}

