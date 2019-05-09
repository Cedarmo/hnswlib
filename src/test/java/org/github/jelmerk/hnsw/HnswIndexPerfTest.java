package org.github.jelmerk.hnsw;


import org.github.jelmerk.Item;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.github.jelmerk.hnsw.NeighbourSelectionHeuristic.SELECT_HEURISTIC;
//import jdk.incubator.vector.IntVetigctor;

public class HnswIndexPerfTest {

    static class MyItem implements Item<Integer, float[]> {
        private final Integer id;
        private final float[] vector;


        MyItem(Integer id, float[] vector) {
            this.id = id;
            this.vector = vector;
        }

        @Override
        public Integer getId() {
            return id;
        }

        @Override
        public float[] getVector() {
            return vector;
        }
    }

    private static final Random random = new Random(42);

    public static void main(String[] args) throws Exception {


        List<MyItem> items = generateRandomItems(100_000, 90);

        System.out.println("Done generating random vectors.");

        long start = System.currentTimeMillis();

        int m = 10;

        HnswIndex<Integer, float[], MyItem, Float> index =
                new HnswIndex.Builder<>(CosineDistance::nonOptimized, items.size())
                        .setM(m)
                        .setLevelLambda(1 / Math.log(m))
                        .setNeighbourHeuristic(SELECT_HEURISTIC)
                        .build();

//        for (MyItem item : items) {
//            index.add(item);
//        }

        index.addAll(items);

        long end = System.currentTimeMillis();


        long duration = end - start;

        System.out.println("Done creating small world. took : " + duration + "ms");

        index.save(new File("/Users/jkuperus/17_million_90.ser"));
    }

    private static List<MyItem> generateRandomItems(int numItems, int vectorSize) {
        List<MyItem> result = new ArrayList<>(numItems);
        for (int i = 0; i < numItems; i++) {


            float[] vector = generateRandomVector(vectorSize);
            result.add(new MyItem(i, vector));
        }
        return result;
    }

    private static float[] generateRandomVector(int size) {
        float[] result = new float[size];
        for (int i = 0; i < size; i++) {
            result[i] = random.nextFloat();
        }
        return result;
    }

}
