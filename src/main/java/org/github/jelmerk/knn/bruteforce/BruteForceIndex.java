package org.github.jelmerk.knn.bruteforce;

import org.github.jelmerk.knn.Index;
import org.github.jelmerk.knn.Item;
import org.github.jelmerk.knn.SearchResult;
import org.github.jelmerk.knn.DistanceFunction;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implementation of {@link Index} that does pairwise comparison and as such can be used as a baseline for measuring
 * approximate nearest neighbours index accuracy.
 *
 * @param <TId> type of the external identifier of an item
 * @param <TVector> The type of the vector to perform distance calculation on
 * @param <TItem> The type of items to connect into small world.
 * @param <TDistance> The type of distance between items (expect any numeric type: float, double, decimal, int, ..).
 */
public class BruteForceIndex<TId, TVector, TItem extends Item<TId, TVector>, TDistance extends Comparable<TDistance>>
        implements Index<TId, TVector, TItem, TDistance>, Serializable {

    private static final long serialVersionUID = 1L;

    private final DistanceFunction<TVector, TDistance> distanceFunction;
    private final Map<TId, TItem> items;

    private BruteForceIndex(BruteForceIndex.Builder<TVector, TDistance> builder) {
        this.distanceFunction = builder.distanceFunction;
        this.items = new ConcurrentHashMap<>();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public TItem get(TId id) {
        return items.get(id);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void add(TItem item) {
        items.putIfAbsent(item.getId(), item);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void remove(TId tId) {
        items.remove(tId);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<SearchResult<TItem, TDistance>> findNearest(TVector vector, int k) {

        Comparator<SearchResult<TItem, TDistance>> comparator = Comparator
                .<SearchResult<TItem, TDistance>>naturalOrder()
                .reversed();

        PriorityQueue<SearchResult<TItem, TDistance>> queue = new PriorityQueue<>(k, comparator);

        for (TItem item : items.values()) {
            TDistance distance = distanceFunction.distance(item.getVector(), vector);

            SearchResult<TItem, TDistance> searchResult = new SearchResult<>(item, distance);
            queue.add(searchResult);

            if (queue.size() > k) {
                queue.poll();
            }
        }

        List<SearchResult<TItem, TDistance>> results = new ArrayList<>(queue.size());

        SearchResult<TItem, TDistance> result;
        while((result = queue.poll()) != null) { // if you iterate over a priority queue the order is not guaranteed
            results.add(0, result);
        }

        return results;
    }

    /**
     * Restores a {@link BruteForceIndex} instance from a file created by invoking the
     * {@link BruteForceIndex#save(File)} method.
     *
     * @param file file to initialize the small world from
     * @param <TItem> The type of items to connect into small world.
     * @param <TDistance> The type of distance between items (expect any numeric type: float, double, decimal, int, ..).
     * @return the Small world restored from a file
     * @throws IOException in case of an I/O exception
     */
    public static <ID, VECTOR, TItem extends Item<ID, VECTOR>, TDistance extends Comparable<TDistance>> BruteForceIndex<ID, VECTOR, TItem, TDistance> load(File file) throws IOException {
        return load(new FileInputStream(file));
    }

    /**
     * Restores a {@link BruteForceIndex} instance from a file created by invoking the
     * {@link BruteForceIndex#save(File)} method.
     *
     * @param inputStream InputStream to initialize the small world from
     * @param <TItem> The type of items to connect into small world.
     * @param <TDistance> The type of distance between items (expect any numeric type: float, double, decimal, int, ..).
     * @return the Small world restored from a file
     * @throws IOException in case of an I/O exception
     * @throws IllegalArgumentException in case the file cannot be read
     */
    @SuppressWarnings("unchecked")
    public static <ID, VECTOR, TItem extends Item<ID, VECTOR>, TDistance extends Comparable<TDistance>>
        BruteForceIndex<ID, VECTOR, TItem, TDistance> load(InputStream inputStream) throws IOException {

        try(ObjectInputStream ois = new ObjectInputStream(inputStream)) {
            return (BruteForceIndex<ID, VECTOR, TItem, TDistance>) ois.readObject();
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("Could not read input file.", e);
        }
    }

    /**
     * Builder for initializing an {@link BruteForceIndex} instance.
     *
     * @param <TVector> The type of the vector to perform distance calculation on
     * @param <TDistance> The type of distance between items (expect any numeric type: float, double, decimal, int, ..).
     */
    public static class Builder <TVector, TDistance extends Comparable<TDistance>> {

        private final DistanceFunction<TVector, TDistance> distanceFunction;

        public Builder(DistanceFunction<TVector, TDistance> distanceFunction) {
            this.distanceFunction = distanceFunction;
        }

        /**
         * Builds the BruteForceIndex instance.
         *
         * @param <TId> type of the external identifier of an item
         * @param <TItem> implementation of the Item interface
         * @return the brute force index instance
         */
        public <TId, TItem extends Item<TId, TVector>> BruteForceIndex<TId, TVector, TItem, TDistance> build() {
            return new BruteForceIndex<>(this);
        }
    }

}
