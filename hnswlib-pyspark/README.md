hnswlib-pyspark
===============

[PySpark](https://spark.apache.org/) integration for hnswlib.

Setup
-----

Pass the following argument to spark

    --packages 'com.github.jelmerk:hnswlib-spark_2.3.1_2.11:0.0.31'

Then install the python module with

    pip install pyspark-hnsw --upgrade
    

Example usage
-------------

Basic:

```python
from pyspark_hnsw.knn import Hnsw

hnsw = Hnsw(identifierCol='id', vectorCol='features', distanceFunction='cosine', m=48, ef=5, k=200,
            efConstruction=200, numPartitions=2, excludeSelf=True)

model = hnsw.fit(index_items)

model.transform(index_items).write.parquet('/path/to/output', mode='overwrite')
```

Advanced:

```python
from pyspark.ml import Pipeline
from pyspark_hnsw.evaluation import KnnEvaluator
from pyspark_hnsw.knn import *
from pyspark_hnsw.linalg import Normalizer

# The cosine distance is obtained with the inner product after normalizing all vectors to unit norm
# this is much faster than calculating the cosine distance directly

normalizer = Normalizer(inputCol='features', outputCol='normalized_features')

hnsw = Hnsw(identifierCol='id', vectorCol='normalized_features', distanceFunction='inner-product', m=48, ef=5, k=200,
            efConstruction=200, numPartitions=2, excludeSelf=True, similarityThreshold=0.4, neighborsCol='approximate')
            
brute_force = BruteForce(identifierCol='id', vectorCol='normalized_features', distanceFunction='inner-product',
                         k=200, numPartitions=2, excludeSelf=True, similarityThreshold=0.4, neighborsCol='exact')
 
pipeline = Pipeline(stages=[normalizer, hnsw, brute_force])

model = pipeline.fit(index_items)

# computing the exact similarity is expensive so only take a small sample
query_items = index_items.sample(0.01)

output = model.transform(query_items)

evaluator = KnnEvaluator(approximateNeighborsCol='approximate', exactNeighborsCol='exact')

accuracy = evaluator.evaluate(output)

print(accuracy)

# save the model
model.write.overwrite.save('/path/to/model')
```

Suggested configuration
-----------------------

- set `executor.instances` to the same value as the numPartitions property of your Hnsw instance
- set `spark.executor.cores` to as high a value as feasible on your executors while not making your jobs impossible to schedule
- set `spark.task.cpus` to the same value as `spark.executor.cores`
- set `spark.sql.shuffle.partitions` to the same value as `executor.instances`
- set `spark.sql.files.maxpartitionbytes` to 134217728 divided by the value assigned to `executor.instances`
- set `spark.scheduler.minRegisteredResourcesRatio` to `1.0`
- set `spark.speculation` to `false`
- set `spark.driver.memory`: to some arbitrary low value for instance "2g" will do because the model does not run on the driver
- set `spark.executor.memory`: to a value appropriate to the size of your data, typically the will be a large value 
- set `spark.yarn.executor.memoryOverhead` to a value higher than executorMemory * 0.10 if you get the "Container killed by YARN for exceeding memory limits" error
- set `spark.kryo.registrator` to com.github.jelmerk.spark.HnswLibKryoRegistrator

Note that as it stands increasing the number of partitions will speed up fitting the model but not querying the model. 

