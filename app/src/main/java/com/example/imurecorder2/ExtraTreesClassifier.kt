package com.example.imurecorder2

import java.io.*
import java.util.Random
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

class ExtraTreesClassifier(
    private val nTrees: Int = 80,
    private val maxDepth: Int = 16,
    private val minSamplesSplit: Int = 8,
    private val mtry: Int? = null,
    private val seed: Long = 1337L
) {

    lateinit var labels: List<String>
        private set

    private var trees: Array<Tree?> = arrayOf()

    fun fit(X: Array<FloatArray>, y: IntArray, labels: List<String>) {
        require(X.isNotEmpty())
        require(X.size == y.size)

        this.labels = labels
        val rnd = Random(seed)
        val featureCount = X[0].size
        val k = mtry ?: max(1, sqrt(featureCount.toDouble()).roundToInt())

        trees = Array(nTrees) {
            buildTree(X, y, depth = 0, rnd = Random(rnd.nextLong()), mtry = k)
        }
    }

    fun predictProba(x: FloatArray): FloatArray {
        val probs = FloatArray(labels.size)
        if (trees.isEmpty()) return probs
        for (t in trees) {
            val c = t!!.predict(x)
            probs[c] += 1f
        }
        for (i in probs.indices) probs[i] /= trees.size.toFloat()
        return probs
    }

    fun predict(x: FloatArray): Int {
        val p = predictProba(x)
        var best = 0
        var bestV = p[0]
        for (i in 1 until p.size) {
            if (p[i] > bestV) {
                bestV = p[i]
                best = i
            }
        }
        return best
    }

    private fun buildTree(
        X: Array<FloatArray>,
        y: IntArray,
        depth: Int,
        rnd: Random,
        mtry: Int
    ): Tree {
        val idx = IntArray(y.size) { it }
        return buildNode(X, y, idx, depth, rnd, mtry)
    }

    private fun buildNode(
        X: Array<FloatArray>,
        y: IntArray,
        idx: IntArray,
        depth: Int,
        rnd: Random,
        mtry: Int
    ): Tree {
        val counts = classCounts(y, idx)
        val majority = argMax(counts)

        if (depth >= maxDepth || idx.size < minSamplesSplit || isPure(counts)) {
            return Tree.Leaf(majority)
        }

        val featureCount = X[0].size
        val features = sampleFeatures(featureCount, mtry, rnd)

        var bestFeature = -1
        var bestThresh = 0f
        var bestScore = Float.POSITIVE_INFINITY
        var bestLeft: IntArray? = null
        var bestRight: IntArray? = null

        for (f in features) {
            var mn = Float.POSITIVE_INFINITY
            var mx = Float.NEGATIVE_INFINITY
            for (i in idx) {
                val v = X[i][f]
                mn = min(mn, v)
                mx = max(mx, v)
            }
            if (mn == mx) continue

            val thr = mn + rnd.nextFloat() * (mx - mn)
            val (l, r) = split(X, idx, f, thr)
            if (l.isEmpty() || r.isEmpty()) continue

            val score = giniSplit(y, l, r)
            if (score < bestScore) {
                bestScore = score
                bestFeature = f
                bestThresh = thr
                bestLeft = l
                bestRight = r
            }
        }

        if (bestFeature == -1 || bestLeft == null || bestRight == null) {
            return Tree.Leaf(majority)
        }

        val leftNode = buildNode(X, y, bestLeft, depth + 1, rnd, mtry)
        val rightNode = buildNode(X, y, bestRight, depth + 1, rnd, mtry)

        return Tree.Node(bestFeature, bestThresh, leftNode, rightNode)
    }

    private fun split(
        X: Array<FloatArray>,
        idx: IntArray,
        feature: Int,
        threshold: Float
    ): Pair<IntArray, IntArray> {
        val left = ArrayList<Int>()
        val right = ArrayList<Int>()
        for (i in idx) {
            if (X[i][feature] <= threshold) left.add(i) else right.add(i)
        }
        return left.toIntArray() to right.toIntArray()
    }

    private fun giniSplit(y: IntArray, left: IntArray, right: IntArray): Float {
        val gL = gini(classCounts(y, left))
        val gR = gini(classCounts(y, right))
        val n = left.size + right.size
        return (left.size.toFloat() / n) * gL + (right.size.toFloat() / n) * gR
    }

    private fun gini(counts: IntArray): Float {
        val sum = counts.sum()
        if (sum == 0) return 0f
        var s = 0f
        for (c in counts) {
            val p = c.toFloat() / sum.toFloat()
            s += p * p
        }
        return 1f - s
    }

    private fun classCounts(y: IntArray, idx: IntArray): IntArray {
        val k = labels.size
        val counts = IntArray(k)
        for (i in idx) counts[y[i]]++
        return counts
    }

    private fun isPure(counts: IntArray): Boolean {
        var nonzero = 0
        for (c in counts) if (c > 0) nonzero++
        return nonzero <= 1
    }

    private fun argMax(arr: IntArray): Int {
        var best = 0
        var bestV = arr[0]
        for (i in 1 until arr.size) {
            if (arr[i] > bestV) {
                bestV = arr[i]
                best = i
            }
        }
        return best
    }

    private fun sampleFeatures(n: Int, k: Int, rnd: Random): IntArray {
        val all = IntArray(n) { it }
        for (i in 0 until k) {
            val j = i + rnd.nextInt(n - i)
            val tmp = all[i]
            all[i] = all[j]
            all[j] = tmp
        }
        return all.copyOfRange(0, k)
    }

    fun saveTo(file: File) {
        DataOutputStream(BufferedOutputStream(FileOutputStream(file))).use { out ->
            out.writeInt(1) // version
            out.writeInt(nTrees)
            out.writeInt(maxDepth)
            out.writeInt(minSamplesSplit)
            out.writeInt(labels.size)
            for (l in labels) out.writeUTF(l)
            out.writeInt(trees.size)
            for (t in trees) {
                t!!.write(out)
            }
        }
    }

    companion object {
        fun load(file: File): ExtraTreesClassifier {
            DataInputStream(BufferedInputStream(FileInputStream(file))).use { inp ->
                val version = inp.readInt()
                require(version == 1)
                val nTrees = inp.readInt()
                val maxDepth = inp.readInt()
                val minSamplesSplit = inp.readInt()
                val labelCount = inp.readInt()
                val labels = ArrayList<String>()
                repeat(labelCount) { labels.add(inp.readUTF()) }
                val tCount = inp.readInt()
                val clf = ExtraTreesClassifier(nTrees, maxDepth, minSamplesSplit)
                clf.labels = labels
                clf.trees = Array(tCount) { Tree.read(inp) }
                return clf
            }
        }
    }

    sealed class Tree {
        abstract fun predict(x: FloatArray): Int
        abstract fun write(out: DataOutputStream)

        class Leaf(private val cls: Int) : Tree() {
            override fun predict(x: FloatArray) = cls
            override fun write(out: DataOutputStream) {
                out.writeByte(0)
                out.writeInt(cls)
            }
        }

        class Node(
            private val feature: Int,
            private val threshold: Float,
            private val left: Tree,
            private val right: Tree
        ) : Tree() {
            override fun predict(x: FloatArray): Int {
                return if (x[feature] <= threshold) left.predict(x) else right.predict(x)
            }

            override fun write(out: DataOutputStream) {
                out.writeByte(1)
                out.writeInt(feature)
                out.writeFloat(threshold)
                left.write(out)
                right.write(out)
            }
        }

        companion object {
            fun read(inp: DataInputStream): Tree {
                val type = inp.readByte().toInt()
                return if (type == 0) {
                    Leaf(inp.readInt())
                } else {
                    val f = inp.readInt()
                    val thr = inp.readFloat()
                    val left = read(inp)
                    val right = read(inp)
                    Node(f, thr, left, right)
                }
            }
        }
    }
}
