package sg.phuc.lob.book;
public interface OrderMap { Order get(long ref); Order put(long ref, Order o); Order remove(long ref); int size(); }
