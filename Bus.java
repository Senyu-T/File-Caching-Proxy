/* Bus.java:
 *    for data trasfer between server and proxy
 */

public class Bus implements java.io.Serializable {
    public byte[] buffer;
    public int size;

    public Bus(int size){
        this.size = size;
        buffer = new byte[size];
    }
}
