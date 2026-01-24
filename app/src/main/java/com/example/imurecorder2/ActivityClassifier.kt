package com.example.imurecorder2

import android.content.Context
import java.io.FileOutputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class ActivityPrediction(val label: String, val confidence: Float)

data class ImuSample(
    val ax: Float, val ay: Float, val az: Float,
    val gx: Float, val gy: Float, val gz: Float,
    val mx: Float, val my: Float, val mz: Float,
    val tsNanos: Long
)

class ActivityClassifier(
    private val windowSize: Int = 120,
    private val trees: Int = 80
) {

    companion object {
        private const val MODEL_FILE = "activity_model.bin"
        private const val DATASETS_DIR = "datasets"
    }

    private val window = ArrayDeque<ImuSample>(windowSize)

    @Volatile private var lastLabel: String = "unknown"
    @Volatile private var lastConf: Float = 0f

    fun getLastPrediction(): Pair<String, Float> = lastLabel to lastConf

    @Volatile private var model: ExtraTreesClassifier? = null
    @Volatile private var labels: List<String> = emptyList()

    private val trainingExecutor = Executors.newSingleThreadExecutor()
    private val trainingInProgress = AtomicBoolean(false)

    fun init(context: Context) {
        val file = File(context.filesDir, MODEL_FILE)


        if (!file.exists()) {
            runCatching {
                context.assets.open(MODEL_FILE).use { input ->
                    FileOutputStream(file).use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }

        if (!file.exists()) return

        runCatching {
            model = ExtraTreesClassifier.load(file)
            labels = model?.labels ?: emptyList()
        }.onFailure {
            model = null
            labels = emptyList()
        }
    }

    fun requestRetrain(context: Context) {
        if (!trainingInProgress.compareAndSet(false, true)) return

        trainingExecutor.execute {
            try {
                val datasetDir = File(context.filesDir, DATASETS_DIR)
                val loaded = loadDataset(datasetDir)

                if (loaded.X.isEmpty()) return@execute

                val clf = ExtraTreesClassifier(nTrees = trees)
                clf.fit(loaded.X, loaded.y, loaded.labels)

                val modelFile = File(context.filesDir, MODEL_FILE)
                clf.saveTo(modelFile)

                model = clf
                labels = clf.labels
            } finally {
                trainingInProgress.set(false)
            }
        }
    }

    fun push(sample: ImuSample): ActivityPrediction? {
        window.addLast(sample)
        if (window.size < windowSize) return null
        if (window.size > windowSize) window.removeFirst()

        val m = model ?: return ActivityPrediction("unknown", 0f)

        val feat = ImuFeatureExtractor.extract(window.toList())
        val proba = m.predictProba(feat)
        var best = 0
        var bestV = proba[0]
        for (i in 1 until proba.size) {
            if (proba[i] > bestV) {
                bestV = proba[i]
                best = i
            }
        }

        val label = m.labels.getOrElse(best) { "unknown" }

        // cache
        lastLabel = label
        lastConf = bestV

        return ActivityPrediction(label, bestV)
    }


    private data class Loaded(
        val X: Array<FloatArray>,
        val y: IntArray,
        val labels: List<String>
    )

    private fun loadDataset(root: File): Loaded {
        if (!root.exists() || !root.isDirectory) return Loaded(emptyArray(), IntArray(0), emptyList())

        val allX = ArrayList<FloatArray>()
        val allY = ArrayList<Int>()
        val labelNames = root.listFiles()?.filter { it.isDirectory }?.map { it.name }?.sorted() ?: emptyList()
        val labelMap = labelNames.withIndex().associate { it.value to it.index }

        for (labelDir in root.listFiles() ?: emptyArray()) {
            if (!labelDir.isDirectory) continue
            val label = labelDir.name
            val cls = labelMap[label] ?: continue

            for (file in labelDir.listFiles() ?: emptyArray()) {
                if (!file.isFile) continue
                val samples = readCsv(file)
                if (samples.size < windowSize) continue

                // cut sequential windows
                var i = 0
                while (i + windowSize <= samples.size) {
                    val win = samples.subList(i, i + windowSize)
                    val feat = ImuFeatureExtractor.extract(win)
                    allX.add(feat)
                    allY.add(cls)
                    i += windowSize
                }
            }
        }

        val X = allX.toTypedArray()
        val y = IntArray(allY.size) { allY[it] }
        return Loaded(X, y, labelNames)
    }

    private fun readCsv(file: File): List<ImuSample> {
        val out = ArrayList<ImuSample>(2048)
        BufferedReader(FileReader(file)).use { br ->
            var line = br.readLine()
            while (line != null) {
                val parts = line.trim().split(",")
                if (parts.size >= 10) {
                    val t = parts[0].toLong()
                    out.add(
                        ImuSample(
                            ax = parts[1].toFloat(),
                            ay = parts[2].toFloat(),
                            az = parts[3].toFloat(),
                            gx = parts[4].toFloat(),
                            gy = parts[5].toFloat(),
                            gz = parts[6].toFloat(),
                            mx = parts[7].toFloat(),
                            my = parts[8].toFloat(),
                            mz = parts[9].toFloat(),
                            tsNanos = t
                        )
                    )
                }
                line = br.readLine()
            }
        }
        return out
    }
}
