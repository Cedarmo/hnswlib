package org.github.jelmerk.knn.hnsw;


import org.eclipse.collections.api.list.primitive.MutableIntList;
import org.eclipse.collections.api.set.primitive.IntSet;
import org.eclipse.collections.impl.list.mutable.primitive.IntArrayList;
import org.eclipse.collections.impl.set.mutable.primitive.IntHashSet;
import org.github.jelmerk.knn.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Implementation of {@link Index} that implements the hnsw algorithm
 *
 * @param <TId> type of the external identifier of an item
 * @param <TVector> The type of the vector to perform distance calculation on
 * @param <TItem> The type of items to connect into small world.
 * @param <TDistance> The type of distance between items (expect any numeric type: float, double, decimal, int, ..).
 *
 * @see <a href="https://arxiv.org/abs/1603.09320">Efficient and robust approximate nearest neighbor search using Hierarchical Navigable Small World graphs</a>
 */
public class HnswIndex<TId, TVector, TItem extends Item<TId, TVector>, TDistance extends Comparable<TDistance>>
        implements Index<TId, TVector, TItem, TDistance>, Serializable {
    // TODO implement Externalizable and store these Nodes more efficiently so the graph loads faster on deserialization

    private static final long serialVersionUID = 1L;

    private final Random random;

    private final DistanceFunction<TVector, TDistance> distanceFunction;

    private final int maxItemCount;
    private final int m;
    private final int maxM;
    private final int maxM0;
    private final double levelLambda;
    private final int ef;
    private final int efConstruction;

    private final AtomicInteger itemCount;
    private final AtomicReferenceArray<TItem> items;
    private final AtomicReferenceArray<Node> nodes;

    private final Map<TId, Integer> lookup;

    private volatile Node entryPoint;

    private final ReentrantLock globalLock;

    private final Pool<VisitedBitSet> visitedBitSetPool;

    private final Timer timer;

    private HnswIndex(HnswIndex.Builder<TVector, TDistance> builder) {

        this.maxItemCount = builder.maxItemCount;
        this.distanceFunction = builder.distanceFunction;
        this.m = builder.m;
        this.maxM = builder.m;
        this.maxM0 = builder.m * 2;
        this.levelLambda = 1 / Math.log(this.m);
        this.efConstruction = Math.max(builder.efConstruction, m);
        this.ef = builder.ef;
        this.random = new Random(builder.randomSeed);

        this.globalLock = new ReentrantLock();

        this.itemCount = new AtomicInteger();
        this.items = new AtomicReferenceArray<>(this.maxItemCount);
        this.nodes = new AtomicReferenceArray<>(this.maxItemCount);

        this.lookup = new ConcurrentHashMap<>();

        // TODO jk: how do we determine the pool size just use num processors or what ?
        this.visitedBitSetPool = new Pool<>(() -> new VisitedBitSet(this.maxItemCount),
                Runtime.getRuntime().availableProcessors());


        this.timer = new Timer("node-cleanup",true);
        this.timer.schedule(new CleanupTask(), TimeUnit.SECONDS.toMillis(1));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public TItem get(TId id) {
        Integer internalId = lookup.get(id);

        Node node = nodes.get(internalId);

        synchronized (node) {
            if (!node.deleted) {
                return items.get(internalId);
            }
        }
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void add(TItem item) {

        int newNodeId = itemCount.getAndUpdate(value -> value == maxItemCount ? maxItemCount :  value + 1);

        if (newNodeId >= this.maxItemCount) {
            throw new IllegalStateException("The number of elements exceeds the specified limit.");
        }

        items.set(newNodeId, item);

        int randomLevel = getRandomLevel(random, this.levelLambda);

        IntArrayList[] connections = new IntArrayList[randomLevel + 1];

        for (int level = 0; level <= randomLevel; level++) {
            int levelM = randomLevel == 0 ? maxM0 : maxM;
            connections[level] = new IntArrayList(levelM);
        }

        Node newNode = new Node(newNodeId, connections);

        nodes.set(newNodeId, newNode);

        lookup.put(item.getId(), newNodeId);

        globalLock.lock();

        Node entrypointCopy = entryPoint;

        if (entryPoint != null && newNode.maxLevel() <= entryPoint.maxLevel()) {
            globalLock.unlock();
        }

        try {
            synchronized (newNode) {

                Node currObj = entrypointCopy;

                if (currObj != null) {

                    if (newNode.maxLevel() < entrypointCopy.maxLevel()) {

                        TDistance curDist = distanceFunction.distance(item.getVector(), items.get(currObj.id).getVector());

                        for (int activeLevel = entrypointCopy.maxLevel(); activeLevel > newNode.maxLevel(); activeLevel--) {

                            boolean changed = true;

                            while (changed) {
                                changed = false;

                                synchronized (currObj) {
                                    MutableIntList candidateConnections = currObj.connections[activeLevel];

                                    for (int i = 0; i < candidateConnections.size(); i++) {

                                        int candidateId = candidateConnections.get(i);

                                        TDistance candidateDistance = distanceFunction.distance(item.getVector(), items.get(candidateId).getVector());
                                        if (lt(candidateDistance, curDist)) {
                                            curDist = candidateDistance;
                                            currObj = nodes.get(candidateId);
                                            changed = true;
                                        }
                                    }
                                }

                            }
                        }
                    }

                    for (int level = Math.min(randomLevel, entrypointCopy.maxLevel()); level >= 0; level--) {
                        PriorityQueue<NodeAndDistance<TDistance>> topCandidates =
                                searchBaseLayer(currObj.id, item.getVector(), efConstruction, level);
                        mutuallyConnectNewElement(item.getVector(), newNodeId, topCandidates, level);
                    }
                }

                // zoom out to the highest level
                if (entryPoint == null || newNode.maxLevel() > entrypointCopy.maxLevel()) {
                    // this is thread safe because we get the global lock when we add a level
                    this.entryPoint = newNode;
                }
            }
        } finally {
            if (globalLock.isHeldByCurrentThread()) {
                globalLock.unlock();
            }
        }
    }

    @Override
    public void remove(TId tId) {
        int internalId = lookup.get(tId);

        Node node = nodes.get(internalId);

        synchronized (node) {

            node.deleted = true;
        }

    }

    private void mutuallyConnectNewElement(TVector dataPoint,
                                           int nodeId,
                                           PriorityQueue<NodeAndDistance<TDistance>> topCandidates,
                                           int level) {

        int bestN = level == 0 ? this.maxM0 : this.maxM;

        MutableIntList nodeConnections = nodes.get(nodeId).connections[level];

        getNeighborsByHeuristic2(topCandidates, m); // this modifies the topCandidates queue

        while (!topCandidates.isEmpty()) {
            int selectedNeighbourId = topCandidates.poll().nodeId;

            nodeConnections.add(selectedNeighbourId);

            Node neighbourNode = nodes.get(selectedNeighbourId);
            synchronized (neighbourNode) {

                MutableIntList neighbourConnectionsAtLevel = neighbourNode.connections[level];

                if (neighbourConnectionsAtLevel.size() < bestN) {
                    neighbourConnectionsAtLevel.add(nodeId);
                } else {
                    // finding the "weakest" element to replace it with the new one

                    TDistance dMax = distanceFunction.distance(dataPoint, items.get(selectedNeighbourId).getVector());

                    Comparator<NodeAndDistance<TDistance>> comparator = Comparator.<NodeAndDistance<TDistance>>naturalOrder().reversed();

                    PriorityQueue<NodeAndDistance<TDistance>> candidates = new PriorityQueue<>(comparator);
                    candidates.add(new NodeAndDistance<>(nodeId, dMax));

                    neighbourConnectionsAtLevel.forEach(id -> {
                        TDistance dist = distanceFunction.distance(items.get(selectedNeighbourId).getVector(), items.get(id).getVector());
                        candidates.add(new NodeAndDistance<>(id, dist));
                    });

                    getNeighborsByHeuristic2(candidates, bestN);

                    neighbourConnectionsAtLevel.clear();

                    while(!candidates.isEmpty()) {
                        neighbourConnectionsAtLevel.add(candidates.poll().nodeId);
                    }
                }
            }
        }
    }

    private void getNeighborsByHeuristic2(PriorityQueue<NodeAndDistance<TDistance>> topCandidates, int m) {

        if (topCandidates.size() < m) {
            return;
        }

        PriorityQueue<NodeAndDistance<TDistance>> queueClosest = new PriorityQueue<>();
        List<NodeAndDistance<TDistance>> returnList = new ArrayList<>();

        while(!topCandidates.isEmpty()) {
            queueClosest.add(topCandidates.poll());
        }

        while(!queueClosest.isEmpty()) {
            if (returnList.size() >= m) {
                break;
            }

            NodeAndDistance<TDistance> currentPair = queueClosest.poll();

            TDistance distToQuery = currentPair.distance;

            boolean good = true;
            for (NodeAndDistance<TDistance> secondPair : returnList) {

                TDistance curdist = distanceFunction.distance(
                    items.get(secondPair.nodeId).getVector(),
                    items.get(currentPair.nodeId).getVector()
                );

                if (lt(curdist, distToQuery)) {
                    good = false;
                    break;
                }

            }
            if (good) {
                returnList.add(currentPair);
            }
        }

        topCandidates.addAll(returnList);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<SearchResult<TItem, TDistance>>findNearest(TVector destination, int k) {

        Node entrypointCopy = entryPoint;

        Node currObj = entrypointCopy;

        TDistance curDist = distanceFunction.distance(destination, items.get(currObj.id).getVector());

        for (int activeLevel = entrypointCopy.maxLevel(); activeLevel > 0; activeLevel--) {

            boolean changed = true;

            while (changed) {
                changed = false;

                synchronized (currObj) {
                    MutableIntList candidateConnections = currObj.connections[activeLevel];

                    for (int i = 0; i < candidateConnections.size(); i++) {

                        int candidateId = candidateConnections.get(i);

                        TDistance candidateDistance = distanceFunction.distance(destination, items.get(candidateId).getVector());
                        if (lt(candidateDistance, curDist)) {
                            curDist = candidateDistance;
                            currObj = nodes.get(candidateId);
                            changed = true;
                        }
                    }
                }

            }
        }

        PriorityQueue<NodeAndDistance<TDistance>> topCandidates = searchBaseLayer(
                currObj.id, destination, Math.max(ef, k), 0);

        while(topCandidates.size() > k) {
            topCandidates.poll();
        }

        List<SearchResult<TItem, TDistance>> results = new ArrayList<>(topCandidates.size());
        while (!topCandidates.isEmpty()) {
            NodeAndDistance<TDistance> pair = topCandidates.poll();
            results.add(0, new SearchResult<>(items.get(pair.nodeId), pair.distance));
        }

        return results;
    }

    private PriorityQueue<NodeAndDistance<TDistance>> searchBaseLayer(
            int entryPointId, TVector destination, int k, int layer) {

        VisitedBitSet visitedBitSet = visitedBitSetPool.borrowObject();

        try {
            PriorityQueue<NodeAndDistance<TDistance>> topCandidates =
                    new PriorityQueue<>(Comparator.<NodeAndDistance<TDistance>>naturalOrder().reversed());
            PriorityQueue<NodeAndDistance<TDistance>> candidateSet = new PriorityQueue<>();

            TDistance distance = distanceFunction.distance(destination, items.get(entryPointId).getVector());

            NodeAndDistance<TDistance> pair = new NodeAndDistance<>(entryPointId, distance);

            topCandidates.add(pair);
            candidateSet.add(pair);
            visitedBitSet.add(entryPointId);

            TDistance lowerBound = distance;

            while (!candidateSet.isEmpty()) {

                NodeAndDistance<TDistance> currentPair = candidateSet.peek();

                if (gt(currentPair.distance, lowerBound)) {
                    break;
                }

                candidateSet.poll();

                Node node = nodes.get(currentPair.nodeId);

                synchronized (node) {

                    MutableIntList candidates = node.connections[layer];

                    for (int i = 0; i < candidates.size(); i++) {

                        int candidateId = candidates.get(i);

                        if (!visitedBitSet.contains(candidateId)) {

                            visitedBitSet.add(candidateId);

                            TItem candidate = items.get(candidateId);

                            TDistance candidateDistance = distanceFunction.distance(destination, candidate.getVector());

                            if (gt(topCandidates.peek().distance, candidateDistance) || topCandidates.size() < k) {

                                NodeAndDistance<TDistance> candidatePair = new NodeAndDistance<>(candidateId, candidateDistance);

                                candidateSet.add(candidatePair);
                                topCandidates.add(candidatePair);

                                if (topCandidates.size() > k) {
                                    topCandidates.poll();
                                }

                                lowerBound = topCandidates.peek().distance;
                            }
                        }
                    }

                }
            }

            return topCandidates;
        } finally {
            visitedBitSet.clear();
            visitedBitSetPool.returnObject(visitedBitSet);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void save(OutputStream out) throws IOException {
        try(ObjectOutputStream oos = new ObjectOutputStream(out)) {
            oos.writeObject(this);
        }
    }

    /**
     * Restores a {@link HnswIndex} instance from a file created by invoking the {@link HnswIndex#save(File)} method.
     *
     * @param file file to initialize the small world from
     * @param <TItem> The type of items to connect into small world.
     * @param <TDistance> The type of distance between items (expect any numeric type: float, double, decimal, int, ..).
     * @return the Small world restored from a file
     * @throws IOException in case of an I/O exception
     */
    public static <ID, VECTOR, TItem extends Item<ID, VECTOR>, TDistance extends Comparable<TDistance>> HnswIndex<ID, VECTOR, TItem, TDistance> load(File file) throws IOException {
        return load(new FileInputStream(file));
    }

    /**
     * Restores a {@link HnswIndex} instance from a file created by invoking the {@link HnswIndex#save(File)} method.
     *
     * @param inputStream InputStream to initialize the small world from
     * @param <TItem> The type of items to connect into small world.
     * @param <TDistance> The type of distance between items (expect any numeric type: float, double, decimal, int, ...).
     * @return the Small world restored from a file
     * @throws IOException in case of an I/O exception
     * @throws IllegalArgumentException in case the file cannot be read
     */
    @SuppressWarnings("unchecked")
    public static <ID, VECTOR, TItem extends Item<ID, VECTOR>, TDistance extends Comparable<TDistance>> HnswIndex<ID, VECTOR, TItem, TDistance> load(InputStream inputStream) throws IOException {
        try(ObjectInputStream ois = new ObjectInputStream(inputStream)) {
            return (HnswIndex<ID, VECTOR, TItem, TDistance>) ois.readObject();
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("Could not read input file.", e);
        }
    }

    private int getRandomLevel(Random generator, double lambda) {
        double r = -Math.log(generator.nextDouble()) * lambda;
        return (int)r;
    }

    private boolean lt(TDistance x, TDistance y) {
        return x.compareTo(y) < 0;
    }

    private boolean gt(TDistance x, TDistance y) {
        return x.compareTo(y) > 0;
    }


    class CleanupTask extends TimerTask {

        @Override
        public void run() {

            IntSet nodesToDelete = collectDeletedNodes();

            for (int i = 0; i < nodes.length(); i++) {
                Node node = nodes.get(i);

                if (node != null) {

                    synchronized (node) {

                        if (referencesDeletedNode(node, nodesToDelete)) {

                        }
                    }
                }
            }


        }

        private IntSet collectDeletedNodes() {
            IntHashSet nodesToDelete = new IntHashSet();

            for (int i = 0; i < nodes.length(); i++) {
                Node node = nodes.get(i);

                if (node != null) {
                    synchronized (node) {
                        if (node.deleted) {
                            nodesToDelete.add(node.id);
                        }

                    }
                }
            }
            return nodesToDelete;
        }

        private boolean referencesDeletedNode(Node node, IntSet deletedNodes) {
            for (int level = node.maxLevel(); level >= 0; level--) {
                MutableIntList connectionsAtLevel = node.connections[level];

                for (int i = 0; i < connectionsAtLevel.size(); i++) {

                    int neighbourId = connectionsAtLevel.get(i);

                    if (deletedNodes.contains(neighbourId)) {
                        return true;
                    }
                }
            }
            return false;
        }
    }


    static class Node implements Serializable {

        private static final long serialVersionUID = 1L;

        final int id;

        final MutableIntList[] connections;

        boolean deleted;

        Node(int id, MutableIntList[] connections) {
            this.id = id;
            this.connections = connections;
        }

        int maxLevel() {
            return this.connections.length - 1;
        }
    }

    static class NodeAndDistance<TDistance extends Comparable<TDistance>> implements Comparable<NodeAndDistance<TDistance>> {

        final int nodeId;
        final TDistance distance;

        NodeAndDistance(int nodeId, TDistance distance) {
            this.nodeId = nodeId;
            this.distance = distance;
        }

        @Override
        public int compareTo(NodeAndDistance<TDistance> o) {
            return distance.compareTo(o.distance);
        }

    }


    /**
     * Builder for initializing an {@link HnswIndex} instance.
     *
     * @param <TVector> The type of the vector to perform distance calculation on
     * @param <TDistance> The type of distance between items (expect any numeric type: float, double, decimal, int, ..).
     */
    public static class Builder <TVector, TDistance extends Comparable<TDistance>> {

        private DistanceFunction<TVector, TDistance> distanceFunction;
        private int maxItemCount;

        private int m = 10;
        private int efConstruction = 200;
        private int ef = 10;

        private int randomSeed = (int) System.currentTimeMillis();

        /**
         * Constructs a new {@link Builder} instance.
         *
         * @param distanceFunction the distance function
         * @param maxItemCount the maximum number of elements in the index
         */
        public Builder(DistanceFunction<TVector, TDistance> distanceFunction, int maxItemCount) {
            this.distanceFunction = distanceFunction;
            this.maxItemCount = maxItemCount;
        }

        /**
         * Sets the number of bi-directional links created for every new element during construction. Reasonable range
         * for m is 2-100. Higher m work better on datasets with high intrinsic dimensionality and/or high recall,
         * while low m work better for datasets with low intrinsic dimensionality and/or low recalls. The parameter
         * also determines the algorithm's memory consumption.
         * As an example for d = 4 random vectors optimal m for search is somewhere around 6, while for high dimensional
         * datasets (word embeddings, good face descriptors), higher M are required (e.g. m = 48, 64) for optimal
         * performance at high recall. The range mM = 12-48 is ok for the most of the use cases. When m is changed one
         * has to update the other parameters. Nonetheless, ef and efConstruction parameters can be roughly estimated by
         * assuming that m * efConstruction is a constant.
         *
         * @param m the number of bi-directional links created for every new element during construction
         * @return the builder.
         */
        public Builder<TVector, TDistance> setM(int m) {
            this.m = m;
            return this;
        }

        /**
         * The parameter has the same meaning as ef, but controls the index time / index accuracy. Bigger efConstruction
         * leads to longer construction, but better index quality. At some point, increasing efConstruction does not
         * improve the quality of the index. One way to check if the selection of ef_construction was ok is to measure
         * a recall for M nearest neighbor search when ef = efConstruction: if the recall is lower than 0.9, then
         * there is room for improvement.
         *
         * @param efConstruction controls the index time / index accuracy
         * @return the builder
         */
        public Builder<TVector, TDistance> setEfConstruction(int efConstruction) {
            this.efConstruction = efConstruction;
            return this;
        }

        /**
         * The size of the dynamic list for the nearest neighbors (used during the search). Higher ef leads to more
         * accurate but slower search. The value ef of can be anything between k and the size of the dataset.
         *
         * @param ef size of the dynamic list for the nearest neighbors
         * @return the builder
         */
        public Builder<TVector, TDistance> setEf(int ef) {
            this.ef = ef;
            return this;
        }

        /**
         * The seed value used to initialize the pseudo random number generator. This is only useful during for testing
         * when indexing on a single thread.
         *
         * @param randomSeed the initial seed
         * @return the builder
         */
        public Builder<TVector, TDistance> setRandomSeed(int randomSeed) {
            this.randomSeed = randomSeed;
            return this;
        }

        /**
         * Build the index.
         *
         * @param <TId> type of the external identifier of an item
         * @param <TItem> implementation of the Item interface
         * @return the hnsw index instance
         */
        public <TId, TItem extends Item<TId, TVector>> HnswIndex<TId, TVector, TItem, TDistance> build() {
            return new HnswIndex<>(this);
        }
    }

}
