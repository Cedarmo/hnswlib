HNSW.Java
=========


Work in progress pure Java implementation of the [the Hierarchical Navigable Small World graphs](https://arxiv.org/abs/1603.09320) algorithm for doing approximate nearest neighbour search.

The index is thread safe and supports adding items to the index incrementally. 

It's flexible interface makes it easy to apply it to any kind and associated distance metric  

It started life as a port of [HNSW.Net](https://github.com/Microsoft/HNSW.Net) but has since been altered significantly


Code example:



    Index<String, float[], Word, Float> index =
        new HnswIndex.Builder<>(CosineDistance::nonOptimized, words.size())
            .build();

    index.addAll(words);
    
    Word item = index.get("king");
    
    List<SearchResult<Word, Float>> nearest = index.findNearest(item.vector, 10);
    
    for (SearchResult<Word, Float> result : nearest) {
        System.out.println(result.getItem().getId() + " " + result.getDistance());
    }