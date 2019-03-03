/* Cache.java:
 *    the cache in each Proxy lru
 */
import java.util.concurrent.ConcurrentHashMap;
import java.util.LinkedList;
import java.io.File;
import java.io.*;
import java.nio.file.Paths;
import java.nio.file.Path;
import java.nio.file.Files;

public class Cache {
    // hash map of all files in the cache
    public ConcurrentHashMap<String, MyFile> fMap;
    // hash map of all pathnames and version, efficient
    public ConcurrentHashMap<String, Long> vMap;
    // all files in cache
    public LinkedList<MyFile> queue;

    public long size;     // Current Cache Size
    public long limit;    // Cache Size Limits

    public Cache(long cacheSize) {
        limit = cacheSize;
        fMap = new ConcurrentHashMap<String, MyFile>();
        vMap = new ConcurrentHashMap<String, Long>();
        queue = new LinkedList<MyFile>();
        size = 0;  // initially empty
    }

    /* these two functions for checking if cached copy exists */
    public synchronized long findVer(String path) {
        if (vMap.containsKey(path)) return vMap.get(path);
        return -1;
    }
    public synchronized MyFile lookUp(String path) {
        return fMap.get(path);
    }


    /* push the read/write copies into the cache,
     * but not to the lru queue, for we are not
     * gonna evict them */
    public synchronized void push(MyFile file) {
        fMap.put(file.path, file);
        vMap.put(file.path, file.version);
        size += file.fileSize;
        assert(size <= limit);
    }

    /* add the master copy into the queue */
    public synchronized void add(MyFile file) {
        queue.addFirst(file);
    }

    /* check if cache limit exceeds */
    public synchronized boolean hasSpace(long len) {
        return (limit >= size + len);
    }

    /* return value: true for actually evicted files,
     *               false for no file could be evicted */
    public synchronized boolean makeRoom(long len) {
        /* if we remove every file but still no space, just return false */
        System.err.println("evict for no sufficient room ");
        int toBeRemoved = 0;
        for (int i = queue.size() - 1; i >= 0; i--) {
            toBeRemoved += (queue.get(i)).fileSize;
            if (toBeRemoved >= len)
                break;
        }
        if (toBeRemoved < len) return false;
        /* LRU eviction */
        for (int i = queue.size() - 1; i >= 0; i--) {
            MyFile f = queue.get(i);
            evict(f);
            if (hasSpace(len))
                return true;
        }
        return false;
    }

    /* for updating writing files */
    public synchronized void cover(String origPath, MyFile newFile) {
        MyFile orig = lookUp(origPath);
        File origF = new File(orig.cachePath);
        origF.delete();
        size = size - orig.fileSize;
        try {
            Files.move(Paths.get(newFile.cachePath), Paths.get(orig.cachePath));
            File cacheF = new File(newFile.cachePath);
            cacheF.delete();
        } catch (IOException e) {
            System.err.println("error in renaming");
            e.printStackTrace();
        }
        newFile.path = origPath;
        newFile.cachePath = orig.cachePath;
        int index = queue.indexOf(orig);
        queue.remove(index);
        queue.addFirst(newFile);
    }

    /* evict the file from the cache */
    public synchronized void evict(MyFile f) {
        size -= f.fileSize;
        if (queue.indexOf(f) > -1)
            queue.remove(queue.indexOf(f));
        File file = new File(f.cachePath);
        System.err.println("Deleting: " + f.cachePath);
        file.delete();
        vMap.remove(f.path);
        fMap.remove(f.path);
    }

    /* most recent used file, move to the lru queue front */
    public synchronized void update(MyFile f) {
        int idx = queue.indexOf(f);
        if (idx <= 0 || idx >= queue.size()) return;
        queue.remove(f);
        queue.addFirst(f);
    }
}
