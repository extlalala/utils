import java.util.*
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

@JvmInline
value class Normalized(val value: Float) {
    init {
        require(value in 0f..1f)
    }
}

class UndoRedoStack<T : Any>(val maxCommands: Int) {
    val undoStack = ArrayDeque<T>()
    val redoStack = ArrayDeque<T>()

    operator fun plusAssign(command: T) = pushCommand(command)

    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }

    fun pushCommand(command: T) {
        redoStack.clear()
        if (maxCommands in 1..undoStack.size) undoStack.removeFirst()
        undoStack.addLast(command)
    }

    fun removeOldestCommand(): T? = undoStack.takeIf { it.isNotEmpty() }?.removeFirst()

    fun undo(): T? {
        if (undoStack.isEmpty()) return null
        val command = undoStack.removeLast()
        redoStack.addLast(command)
        return command
    }

    fun redo(): T? {
        if (redoStack.isEmpty()) return null
        val command = redoStack.removeLast()
        undoStack.addLast(command)
        return command
    }

    inline fun replaceAll(mapper: (T) -> T) {
        repeat(undoStack.size) { undoStack.addFirst(mapper(undoStack.removeLast())) }
        repeat(redoStack.size) { redoStack.addFirst(mapper(redoStack.removeLast())) }
    }

    override fun toString() = "[us: ${undoStack.joinToString()}, rs: ${redoStack.joinToString()}]"
}

enum class ExceptionHandlingPolicy {
    Throw,
    Log,
    Ignore;

    inline fun check(value: Boolean, message: () -> String = { "Check failed." }): Boolean =
        value.alsoIfFalse { handle(message) }

    inline fun handle(message: () -> String) {
        when (this) {
            Throw -> error(message())
            Log -> System.err.println(message())

            Ignore -> {}
        }
    }

    inline fun <R> runInScope(message: () -> R): R? {
        return try {
            message()
        } catch (e: Throwable) {
            handle { e.toString() }
            null
        }
    }
}

class CommandHistoryBuffer(
    bufferSize: Int,
    val exceptionHandling: ExceptionHandlingPolicy = ExceptionHandlingPolicy.Log,
) {
    companion object {
        private fun calculateLength(bufferSize: Int, start: Int, end: Int): Int =
            if (start <= end) end - start else (end + bufferSize - start)

        private fun circularToLinearIndex(bufferSize: Int, start: Int, position: Int): Int = when {
            start <= position -> position - start
            else -> position + bufferSize - start
        }
    }

    private var buffer = IntArray(bufferSize)
    var bufferSize: Int
        get() = buffer.size
        set(newSize) {
            if (newSize == buffer.size) return
            exceptionHandling.check(buffer.size < newSize) { "New buffer size must be larger than current size" }.alsoIfFalse { return }
            exceptionHandling.check(currentWriter == null) { "Cannot resize buffer during active command recording" }.alsoIfFalse { return }
            val newBuffer = IntArray(newSize)
            System.arraycopy(buffer, start, newBuffer, 0, buffer.size - start)
            System.arraycopy(buffer, 0, newBuffer, buffer.size - start, start)
            commandBoundaries.replaceAll { (commandStart, commandEnd) ->
                circularToLinearIndex(buffer.size, start, commandStart) to circularToLinearIndex(buffer.size, start, commandEnd)
            }
            undoBufferEnd = calculateLength(buffer.size, start, undoBufferEnd)
            buffer = newBuffer
            start = 0
        }
    private var start = 0
    private var undoBufferEnd = 0
    private val commandBoundaries = UndoRedoStack<Pair<Int, Int>>(-1)
    var currentWriter: CommandWriter? = null
        private set

    private fun incrementIndex(index: Int, delta: Int = 1): Int = (index + delta).mod(buffer.size)

    fun removeOldestCommand() =
        commandBoundaries.removeOldestCommand()?.let { (_, commandEnd) -> start = commandEnd }

    fun clear() {
        start = 0
        undoBufferEnd = 0
        commandBoundaries.clear()
    }

    private fun inBounds(start: Int, end: Int, index: Int): Boolean = when {
        start < end -> index in start..<end
        end < start -> index in start..<buffer.size || index in 0..<end
        else -> false
    }

    inner class CommandWriter(private val commandStart: Int) : AutoCloseable {
        private var index = commandStart
        val barrier = incrementIndex(commandStart, -1)
        val finalized get() = currentWriter !== this
        var dropped = false
            private set

        fun put(value: Int): Boolean {
            if (dropped) return false
            exceptionHandling.check(!finalized) { "CommandWriter finalized" }.alsoIfFalse { return false }
            exceptionHandling.check(index != barrier) { "Command exceeds buffer capacity (max: ${buffer.size})" }
                .alsoIfFalse {
                    clear()
                    reset()
                    dropped = true
                    return false
                }
            buffer[index] = value
            index = incrementIndex(index)
            return true
        }

        override fun close() {
            exceptionHandling.check(!finalized) { "CommandWriter finalized" }.alsoIfFalse { return }
            if (index != commandStart) {
                commandBoundaries += undoBufferEnd to index
                while (inBounds(start, undoBufferEnd, index)) removeOldestCommand()
                undoBufferEnd = index
            }
            currentWriter = null
        }

        fun reset() = run { index = commandStart }
    }

    inner class CommandReader(commandStart: Int, private val commandEnd: Int) : IntIterator() {
        private var index = commandStart

        override operator fun hasNext(): Boolean = index != commandEnd

        override fun nextInt(): Int {
            exceptionHandling.check(index != commandEnd) { "$index out of Bounds ?..<$commandEnd" }.alsoIfFalse { return 0 }
            return buffer[index]
                .also { index = incrementIndex(index) }
        }
    }

    fun beginCommandRecording(): CommandWriter? {
        exceptionHandling.check(currentWriter == null) { "assertNoCommandRecording failed" }.alsoIfFalse { return null }
        return CommandWriter(undoBufferEnd).also { currentWriter = it }
    }

    inline fun getOrCreateCommandWriter(init: CommandWriter.() -> Unit): CommandWriter? {
        return currentWriter ?: beginCommandRecording()?.apply(init)
    }

    fun undo(): CommandReader? {
        exceptionHandling.check(currentWriter == null) { "assertNoCommandRecording failed" }.alsoIfFalse { return null }
        return commandBoundaries.undo()
            ?.let { (start, end) ->
                this.undoBufferEnd = start
                CommandReader(start, end)
            }
    }

    fun redo(): CommandReader? {
        exceptionHandling.check(currentWriter == null) { "assertNoCommandRecording failed" }.alsoIfFalse { return null }
        return commandBoundaries.redo()
            ?.let { (start, end) ->
                this.undoBufferEnd = end
                CommandReader(start, end)
            }
    }

    fun bufferToString(start: Int = this.start, end: Int = this.undoBufferEnd): String = buildString {
        var index = start
        while (index != end) {
            append("${buffer[index]}, ")
            index = incrementIndex(index)
        }
    }
}

class Pool<T>(val new: () -> T, val capacity: Int = 12) {
    val items = ArrayList<T>(capacity)

    fun obtain(): T = items.removeLastOrNull() ?: new()

    fun free(value: T) {
        if (items.size < capacity) items += value
    }

    inline fun <R> with(fn: (T) -> R): R {
        val value = obtain()
        return try {
            fn(value)
        } finally {
            free(value)
        }
    }

    inline fun <R> with(fn: (T, T) -> R): R {
        val a = obtain()
        val b = obtain()
        return try {
            fn(a, b)
        } finally {
            free(a)
            free(b)
        }
    }
}

fun ClosedRange<Float>.lerp(progress: Float): Float = start + length * progress
fun ClosedRange<Float>.progress(value: Float): Float = (value - start) / length
val ClosedRange<Float>.length get() = endInclusive - start

sealed interface Either<L, R> {
    data class Left<L, R>(val value: L) : Either<L, R>
    data class Right<L, R>(val value: R) : Either<L, R>
}

inline fun <L, R, L1, R1> Either<L, R>.map(mapLeft: (L) -> L1, mapRight: (R) -> R1): Either<L1, R1> =
    when (this) {
        is Either.Left -> Either.Left(mapLeft(value))
        is Either.Right -> Either.Right(mapRight(value))
    }

inline fun <L, R, L1, R1> Either<L, R>.flatMap(mapLeft: (L) -> Either.Left<L1, R1>, mapRight: (R) -> Either.Right<L1, R1>): Either<L1, R1> =
    when (this) {
        is Either.Left -> mapLeft(value)
        is Either.Right -> mapRight(value)
    }

inline fun <L, R, V> Either<L, R>.destruct(mapLeft: (L) -> V, mapRight: (R) -> V): V =
    when (this) {
        is Either.Left -> mapLeft(value)
        is Either.Right -> mapRight(value)
    }

fun Int.setBits(bits: Int, flag: Boolean): Int = if (flag) this.or(bits) else this.and(bits.inv())

@OptIn(ExperimentalContracts::class)
inline fun <T : R, R> T.transformIf(predicate: (T) -> Boolean, fn: (T) -> R): R {
    contract {
        callsInPlace(predicate, InvocationKind.EXACTLY_ONCE)
        callsInPlace(fn, InvocationKind.AT_MOST_ONCE)
    }
    return if (predicate(this)) fn(this) else this
}

@OptIn(ExperimentalContracts::class)
inline fun <T : R, R> T.transformIf(condition: Boolean, fn: (T) -> R): R {
    contract { callsInPlace(fn, InvocationKind.AT_MOST_ONCE) }
    return if (condition) fn(this) else this
}

@OptIn(ExperimentalContracts::class)
inline fun <R> Boolean.ifTrue(fn: () -> R): R? {
    contract { callsInPlace(fn, InvocationKind.AT_MOST_ONCE) }
    return if (this) fn() else null
}

@OptIn(ExperimentalContracts::class)
inline fun Boolean.alsoIfTrue(fn: () -> Unit): Boolean {
    contract { callsInPlace(fn, InvocationKind.AT_MOST_ONCE) }
    if (this) fn()
    return this
}

@OptIn(ExperimentalContracts::class)
inline fun Boolean.alsoIfFalse(fn: () -> Unit): Boolean {
    contract { callsInPlace(fn, InvocationKind.AT_MOST_ONCE) }
    if (!this) fn()
    return this
}

@OptIn(ExperimentalContracts::class)
inline fun <T> T.alsoIf(condition: Boolean, fn: (T) -> Unit): T {
    contract { callsInPlace(fn, InvocationKind.AT_MOST_ONCE) }
    if (condition) fn(this)
    return this
}

@OptIn(ExperimentalContracts::class)
inline fun <R> valueIf(condition: Boolean, fn: () -> R): R? {
    contract { callsInPlace(fn, InvocationKind.AT_MOST_ONCE) }
    return if (condition) fn() else null
}

@OptIn(ExperimentalContracts::class)
inline fun <T> T?.exists(predicate: (T) -> Boolean): Boolean {
    contract { callsInPlace(predicate, InvocationKind.AT_MOST_ONCE) }
    return this != null && predicate(this)
}

inline fun <T : Any> bfsSequence(seed: T, crossinline fn: (T) -> Iterable<T>): Sequence<T> = sequence {
    val queue = ArrayDeque<T>().also { it.add(seed) }
    val visited = mutableSetOf<T>()

    while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        if (visited.add(current)) {
            yield(current)
            fn(current).forEach(queue::addLast)
        }
    }
}

inline fun <T : Any> dfsSequence(seed: T, crossinline fn: (T) -> List<T>): Sequence<T> = sequence {
    val stack = ArrayDeque<T>().also { it.add(seed) }
    val visited = mutableSetOf<T>()

    while (stack.isNotEmpty()) {
        val current = stack.pop()
        if (visited.add(current)) {
            yield(current)
            fn(current).asReversed().forEach(stack::push)
        }
    }
}

class AverageValue(val latestValueWeight: Float) {
    private var _curr = Float.NaN
    val curr get() = _curr

    init {
        require(latestValueWeight in 0f..1f)
    }

    fun add(value: Float) {
        if (_curr.isNaN()) {
            _curr = value
            return
        }
        _curr = _curr * (1 - latestValueWeight) + value * latestValueWeight
    }
}

data class CyclicTimer(val interval: Float, var accumulatedTime: Float = 0f) {
    inline fun <R> tick(deltaTime: Float, fn: () -> R): R? {
        accumulatedTime += deltaTime
        return (accumulatedTime >= interval).ifTrue { fn().also { accumulatedTime -= interval } }
    }

    fun reset() {
        accumulatedTime = 0f
    }
}

fun calculateContentPosition(containerPosition: Float, containerSize: Float, contentSize: Float, norAlignment: Float = 0.5f): Float =
    containerPosition + (containerSize - contentSize) * norAlignment

fun calculateContentAlignment(containerPosition: Float, containerSize: Float, contentSize: Float, contentCenter: Float): Float =
    (contentCenter - containerPosition - contentSize / 2) / (containerSize - contentSize)
