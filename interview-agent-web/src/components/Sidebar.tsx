/**
 * @author: 陈龙强
 */
import {useChatStore} from '../store/chatStore'
import {useAuthStore} from '../store/authStore'
import {HistorySidebar} from './HistorySidebar'

interface SidebarProps {
    onLoadSession?: (sessionId: string) => void
    onNewChat?: () => void
    onViewQuestionBank?: () => void
    questionBankActive?: boolean
    /** 删除会话成功后通知主页面处理当前会话切换。 */
    onSessionDeleted?: (sessionId: string) => void
}

export function Sidebar({onLoadSession, onNewChat, onViewQuestionBank, questionBankActive, onSessionDeleted}: SidebarProps) {
    const {clearMessages, connected} = useChatStore()
    const username = useAuthStore((s) => s.username)
    const logout = useAuthStore((s) => s.logout)

    return (
        <div className="w-64 bg-gray-900 text-white flex flex-col h-screen">
            <div className="p-4 border-b border-gray-700">
                <h1 className="text-lg font-bold">InterviewAgent</h1>
                <p className="text-xs text-gray-400 mt-1">AI 模拟面试系统</p>
            </div>

            <div className="p-3 space-y-2">
                <button
                    onClick={onNewChat || clearMessages}
                    className="w-full py-2 px-4 border border-gray-600 rounded-lg text-sm hover:bg-gray-800 transition"
                >
                    + 新建对话
                </button>
                <button
                    onClick={onViewQuestionBank}
                    aria-current={questionBankActive ? 'page' : undefined}
                    className={`w-full py-2 px-4 rounded-lg text-sm transition text-left flex items-center gap-2 ${
                        questionBankActive
                            ? 'bg-indigo-600 text-white'
                            : 'border border-gray-700 text-gray-200 hover:bg-gray-800'
                    }`}
                >
                    <span aria-hidden="true">▤</span> 查看题库
                </button>
            </div>

            <div className="flex-1 overflow-hidden">
                <HistorySidebar
                    onLoadSession={onLoadSession}
                    onSessionDeleted={onSessionDeleted}
                />
            </div>

            <div className="p-3 border-t border-gray-700 space-y-2">
                <div className="flex items-center gap-2 text-xs">
                    <span className={`w-2 h-2 rounded-full ${connected ? 'bg-green-500' : 'bg-red-500'}`}/>
                    <span className="text-gray-400">{connected ? '已连接' : '未连接'}</span>
                </div>
                {username && (
                    <div className="flex items-center justify-between text-xs">
                        <span className="text-gray-300">{username}</span>
                        <button
                            onClick={logout}
                            className="text-gray-500 hover:text-red-400 transition"
                        >
                            退出
                        </button>
                    </div>
                )}
            </div>
        </div>
    )
}
