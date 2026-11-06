import {getWithAuth} from './auth'

export interface QuestionChunk {
    id: string
    content: string
    sourceFile: string
    metadata: Record<string, unknown>
}

export interface QuestionSource {
    sourceFile: string
    count: number
}

export interface QuestionChunkPage {
    chunks: QuestionChunk[]
    nextOffset: number
    hasMore: boolean
}

export function getQuestionSources(): Promise<QuestionSource[]> {
    return getWithAuth<QuestionSource[]>('/api/question-bank/sources')
}

export function getQuestionChunks(sourceFile: string, offset = 0, limit = 20): Promise<QuestionChunkPage> {
    return getWithAuth<QuestionChunkPage>(`/api/question-bank/chunks?sourceFile=${encodeURIComponent(sourceFile)}&offset=${offset}&limit=${limit}`)
}
