import {useEffect, useState} from 'react'
import {getQuestionChunks, getQuestionSources, type QuestionChunk, type QuestionSource} from '../api/questionBank'

interface QuestionBankViewProps {
    onBack: () => void
}

export function QuestionBankView({onBack}: QuestionBankViewProps) {
    const [sources, setSources] = useState<QuestionSource[]>([])
    const [selectedSource, setSelectedSource] = useState<QuestionSource | null>(null)
    const [chunks, setChunks] = useState<QuestionChunk[]>([])
    const [nextOffset, setNextOffset] = useState(0)
    const [hasMore, setHasMore] = useState(false)
    const [loadingSources, setLoadingSources] = useState(true)
    const [loadingChunks, setLoadingChunks] = useState(false)
    const [loadingMore, setLoadingMore] = useState(false)
    const [error, setError] = useState<string | null>(null)
    const [sourcesReloadKey, setSourcesReloadKey] = useState(0)
    const [chunksReloadKey, setChunksReloadKey] = useState(0)
    const selectedFile = selectedSource?.sourceFile

    useEffect(() => {
        let active = true
        getQuestionSources()
            .then((items) => {
                if (active) setSources(items)
            })
            .catch((reason) => {
                if (active) setError(reason instanceof Error ? reason.message : '题库列表加载失败')
            })
            .finally(() => {
                if (active) setLoadingSources(false)
            })
        return () => { active = false }
    }, [sourcesReloadKey])

    useEffect(() => {
        if (!selectedFile) return
        let active = true
        getQuestionChunks(selectedFile)
            .then((page) => {
                if (!active) return
                setChunks(page.chunks)
                setNextOffset(page.nextOffset)
                setHasMore(page.hasMore)
            })
            .catch((reason) => {
                if (active) setError(reason instanceof Error ? reason.message : '题库加载失败')
            })
            .finally(() => {
                if (active) setLoadingChunks(false)
            })
        return () => { active = false }
    }, [selectedFile, chunksReloadKey])

    const refreshSources = () => {
        setLoadingSources(true)
        setError(null)
        setSourcesReloadKey((key) => key + 1)
    }

    const openSource = (source: QuestionSource) => {
        setSelectedSource(source)
        setLoadingChunks(true)
        setError(null)
        setChunks([])
        setHasMore(false)
        setNextOffset(0)
    }

    const refreshChunks = () => {
        setLoadingChunks(true)
        setError(null)
        setChunks([])
        setHasMore(false)
        setNextOffset(0)
        setChunksReloadKey((key) => key + 1)
    }

    const loadMore = async () => {
        if (loadingMore || !hasMore || !selectedFile) return
        setLoadingMore(true)
        setError(null)
        try {
            const page = await getQuestionChunks(selectedFile, nextOffset)
            setChunks((current) => [...current, ...page.chunks])
            setNextOffset(page.nextOffset)
            setHasMore(page.hasMore)
        } catch (reason) {
            setError(reason instanceof Error ? reason.message : '加载更多失败')
        } finally {
            setLoadingMore(false)
        }
    }

    const totalChunks = sources.reduce((count, source) => count + source.count, 0)

    return (
        <main className="min-w-0 flex-1 overflow-y-auto bg-slate-50 text-slate-900">
            <div className="mx-auto max-w-6xl px-6 py-8 lg:px-10">
                <div className="mb-6 flex items-center justify-between gap-3">
                    <button onClick={onBack} className="text-sm text-slate-500 transition hover:text-indigo-700">
                        ← 返回会话
                    </button>
                    <button
                        onClick={selectedSource ? refreshChunks : refreshSources}
                        disabled={loadingSources || loadingChunks || loadingMore}
                        className="rounded-lg border border-slate-200 bg-white px-4 py-2 text-sm font-medium text-slate-700 shadow-sm transition hover:border-indigo-300 hover:text-indigo-700 disabled:opacity-50"
                    >
                        {selectedSource ? '刷新当前题库' : '刷新题库列表'}
                    </button>
                </div>

                <section className="overflow-hidden rounded-2xl bg-gradient-to-br from-slate-900 via-slate-800 to-indigo-900 px-7 py-8 text-white shadow-lg md:px-9">
                    <p className="mb-3 text-xs font-semibold tracking-[0.22em] text-indigo-200">QUESTION BANK {selectedSource ? '· CHUNKS' : '· LIBRARY'}</p>
                    <h2 className="break-words text-3xl font-bold tracking-tight">{selectedSource ? selectedSource.sourceFile : '已解析题库'}</h2>
                    <p className="mt-3 max-w-2xl text-sm leading-6 text-slate-300">
                        {selectedSource
                            ? '查看该题库解析出的题目片段和结构化元数据。向量数据不会显示。'
                            : '按上传的题库文件分类。选择一个题库后，再查看其中的 chunks 和元数据。'}
                    </p>
                </section>

                {!selectedSource ? (
                    <>
                        <div className="my-6 grid gap-3 sm:grid-cols-2">
                            <Stat label="题库文件" value={sources.length} unit="个"/>
                            <Stat label="已入库题目" value={totalChunks} unit="道"/>
                        </div>
                        <div className="mb-4">
                            <p className="text-xs font-semibold uppercase tracking-widest text-indigo-600">Uploaded libraries</p>
                            <h3 className="mt-1 text-xl font-semibold">选择题库</h3>
                        </div>
                        {loadingSources && <LoadingMessage text="正在读取题库列表..."/>}
                        {!loadingSources && sources.length === 0 && !error && (
                            <div className="rounded-xl border border-dashed border-slate-300 bg-white px-6 py-14 text-center">
                                <div className="mb-3 text-3xl text-indigo-400">▤</div>
                                <p className="font-medium">还没有入库的题库</p>
                                <p className="mt-2 text-sm text-slate-500">在会话中上传题库，解析并写入成功后即可在这里查看。</p>
                                <button onClick={onBack} className="mt-5 rounded-lg bg-indigo-600 px-4 py-2 text-sm font-medium text-white hover:bg-indigo-700">返回会话</button>
                            </div>
                        )}
                        <div className="grid gap-3 md:grid-cols-2">
                            {sources.map((source) => (
                                <button key={source.sourceFile} onClick={() => openSource(source)} className="group rounded-xl border border-slate-200 bg-white p-5 text-left shadow-sm transition hover:border-indigo-300 hover:shadow-md">
                                    <div className="flex items-start gap-3">
                                        <span className="rounded-lg bg-indigo-50 px-3 py-2 text-lg text-indigo-600">▤</span>
                                        <div className="min-w-0 flex-1">
                                            <p className="break-all font-semibold text-slate-800">{source.sourceFile}</p>
                                            <p className="mt-2 text-xs text-slate-500">{source.count} 个 chunks</p>
                                        </div>
                                    </div>
                                    <p className="mt-5 border-t border-slate-100 pt-3 text-sm font-medium text-indigo-600 group-hover:text-indigo-800">查看题目与元数据 →</p>
                                </button>
                            ))}
                        </div>
                        {error && <ErrorMessage error={error} onRetry={refreshSources}/>}
                    </>
                ) : (
                    <>
                        <button onClick={() => { setSelectedSource(null); setError(null) }} className="my-5 text-sm font-medium text-indigo-700 hover:text-indigo-900">← 全部题库</button>
                        <div className="mb-6 grid gap-3 sm:grid-cols-2">
                            <Stat label="该题库已入库" value={selectedSource.count} unit="道题"/>
                            <Stat label="当前已载入" value={chunks.length} unit="个 chunks"/>
                        </div>
                        <div className="mb-4 flex items-end justify-between gap-3">
                            <div>
                                <p className="text-xs font-semibold uppercase tracking-widest text-indigo-600">Indexed entries</p>
                                <h3 className="mt-1 text-xl font-semibold">题目片段</h3>
                            </div>
                            <span className="text-xs text-slate-500">每次加载 20 条</span>
                        </div>
                        {loadingChunks && <LoadingMessage text="正在读取题目片段..."/>}
                        {!loadingChunks && chunks.length === 0 && !error && <LoadingMessage text="这个题库还没有入库的题目。"/>}
                        <div className="space-y-4">
                            {chunks.map((chunk, index) => <ChunkCard key={`${chunk.id}-${index}`} chunk={chunk} index={index + 1}/>)}
                        </div>
                        {error && <ErrorMessage error={error} onRetry={refreshChunks}/>}
                        {!loadingChunks && hasMore && (
                            <div className="mt-7 text-center">
                                <button onClick={() => void loadMore()} disabled={loadingMore} className="rounded-lg border border-indigo-200 bg-white px-6 py-2.5 text-sm font-semibold text-indigo-700 shadow-sm hover:bg-indigo-50 disabled:opacity-50">
                                    {loadingMore ? '加载中...' : '加载更多 chunks'}
                                </button>
                            </div>
                        )}
                    </>
                )}
            </div>
        </main>
    )
}

function Stat({label, value, unit}: {label: string; value: number; unit: string}) {
    return <div className="rounded-xl border border-slate-200 bg-white px-5 py-4 shadow-sm">
        <div className="text-xs text-slate-500">{label}</div>
        <div className="mt-1 text-2xl font-semibold">{value}<span className="ml-2 text-sm font-normal text-slate-500">{unit}</span></div>
    </div>
}

function LoadingMessage({text}: {text: string}) {
    return <div className="rounded-xl border border-slate-200 bg-white p-10 text-center text-sm text-slate-500">{text}</div>
}

function ErrorMessage({error, onRetry}: {error: string; onRetry: () => void}) {
    return <div role="alert" className="mt-5 rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">
        {error} <button onClick={onRetry} className="ml-2 font-semibold underline">重新加载</button>
    </div>
}

function ChunkCard({chunk, index}: {chunk: QuestionChunk; index: number}) {
    const metadata = chunk.metadata ?? {}
    const skills = Array.isArray(metadata.skills)
        ? metadata.skills.filter((value): value is string => typeof value === 'string')
        : []
    const question = chunk.content.split('\n参考答案：')[0].trim() || chunk.content

    return (
        <article className="overflow-hidden rounded-xl border border-slate-200 bg-white shadow-sm">
            <div className="flex flex-wrap items-center gap-2 border-b border-slate-100 px-5 py-3 text-xs">
                <span className="rounded-md bg-indigo-50 px-2 py-1 font-semibold text-indigo-700">CHUNK {String(index).padStart(2, '0')}</span>
                <span className="min-w-0 break-all text-slate-500">{chunk.sourceFile || '未知来源'}</span>
                <span className="ml-auto font-mono text-slate-400">{chunk.id}</span>
            </div>
            <div className="px-5 py-5">
                <p className="mb-2 text-xs font-semibold text-slate-400">解析出的题目</p>
                <p className="whitespace-pre-wrap break-words text-sm leading-7 text-slate-800">{question}</p>
                <div className="mt-4 flex flex-wrap gap-2">
                    {typeof metadata.type === 'string' && <Badge label={`类型 · ${metadata.type}`}/>}
                    {typeof metadata.difficulty === 'string' && <Badge label={`难度 · ${metadata.difficulty}`}/>}
                    {skills.map((skill) => <Badge key={skill} label={skill}/>)}
                </div>
                <div className="mt-5 grid gap-2 border-t border-slate-100 pt-4 text-sm md:grid-cols-2">
                    <details className="group rounded-lg bg-slate-50 px-4 py-3">
                        <summary className="cursor-pointer font-medium text-slate-700">查看完整 chunk 内容</summary>
                        <pre className="mt-3 whitespace-pre-wrap break-words font-sans text-xs leading-6 text-slate-600">{chunk.content}</pre>
                    </details>
                    <details className="group rounded-lg bg-slate-50 px-4 py-3">
                        <summary className="cursor-pointer font-medium text-slate-700">查看原始元数据</summary>
                        <pre className="mt-3 overflow-x-auto whitespace-pre-wrap break-words text-xs leading-6 text-slate-600">{JSON.stringify(metadata, null, 2)}</pre>
                    </details>
                </div>
            </div>
        </article>
    )
}

function Badge({label}: {label: string}) {
    return <span className="rounded-full bg-slate-100 px-2.5 py-1 text-xs text-slate-600">{label}</span>
}
