package dev.ujhhgtg.wekit.features.items.scripting_java

import android.content.ContentValues
import bsh.Interpreter
import com.tencent.mm.pluginsdk.ui.chat.ChatFooter
import dev.ujhhgtg.comptime.This
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseListenerApi
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.features.items.chat.ChatInputBarEnhancements
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToastSuspend
import dev.ujhhgtg.wekit.utils.fs.KnownPaths
import dev.ujhhgtg.wekit.utils.fs.createDirsSafe
import dev.ujhhgtg.wekit.utils.serialization.XmlUtils.extractXmlAttr
import dev.ujhhgtg.wekit.utils.serialization.XmlUtils.extractXmlTag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.hd.wauxv.data.bean.MsgInfoBean
import me.hd.wauxv.data.bean.PayMsgBean
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText

@Feature(name = "脚本引擎 (Java)", categories = ["脚本 (Java)"], description = "执行 Java 脚本")
object JavaScriptingHook : SwitchFeature(), IResolveDex, WeDatabaseListenerApi.IUpdateListener, WeDatabaseListenerApi.IInsertListener {

    private val TAG = This.Class.simpleName

    private val SCRIPTS_DIR = (KnownPaths.moduleData / "scripts_java").createDirsSafe()

    val scripts = ConcurrentHashMap<String, JavaPlugin>()

    private val methodPayMsg by dexMethod {
        matcher {
            usingEqStrings("[onRecv PayerMsg]，newMsg.msgType：%s")
        }
    }

    override fun onEnable() {
        WeDatabaseListenerApi.addListener(this)

        WeMessageApi.methodMsgInfoStorageInsertMessage.hookAfter {
            val msgObj = args[0] ?: return@hookAfter
            val msgBean = MsgInfoBean(msgObj)
            JavaEngine.executeAllOnHandleMsg(scripts, msgBean)
        }

        ChatInputBarEnhancements.methodSendMessage.hookBefore {
            val chatFooter = thisObject.reflekt().firstField {
                type = ChatFooter::class
            }.get()!! as ChatFooter
            val text = chatFooter.lastText
            JavaEngine.executeAllOnClickSendBtn(scripts, this, text)
        }

        methodPayMsg.hookBefore {
            val g2Var = args[0] ?: return@hookBefore
            val payMsgBean = PayMsgBean(g2Var)
            JavaEngine.executeAllOnRecvPayMsg(scripts, payMsgBean)
        }

        CoroutineScope(Dispatchers.IO).launch {
            WeLogger.d(TAG, "loading java scripts...")
            for (scriptDir in SCRIPTS_DIR.listDirectoryEntries().filter { it.isDirectory() }) {
                val dirName = scriptDir.name
                val mainFile = scriptDir / "main.java"
                val infoFile = scriptDir / "info.prop"
                if (!mainFile.exists() || !infoFile.exists()) {
                    WeLogger.w(TAG, "skipping '$dirName': missing main.java or info.prop")
                    continue
                }

                val content = runCatching { mainFile.readText() }.getOrElse { continue }
                val infoPropContent = runCatching { infoFile.readText() }.getOrElse { continue }
                val info = JavaPlugin.parseInfoProp(infoPropContent)
                WeLogger.d(TAG, "loaded script, name='${info.name}', length=${content.length}")

                val plugin = JavaPlugin(
                    name = dirName,
                    dir = scriptDir,
                    info = info,
                    content = content,
                    interpreter = Interpreter(null, "")
                )
                scripts[dirName] = plugin
            }
            showToastSuspend("已加载 ${scripts.size} 个 Java 脚本")

            JavaEngine.executeAllOnLoad(scripts)
        }
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(this)
        JavaHookApi.unhookEverything()
        JavaEngine.executeAllOnUnload(scripts)
        scripts.clear()
    }

    override fun onInsert(table: String, values: ContentValues) {
        if (table == "fmessage_msginfo") {
            val isSend = values.getAsInteger("isSend") ?: 0
            if (isSend == 0) {
                val msgContent = values.getAsString("msgContent") ?: ""
                val fromusername = extractXmlAttr(msgContent, "fromusername").takeIf { it.isNotEmpty() }
                    ?: extractXmlTag(msgContent, "fromusername")
                val ticket = extractXmlAttr(msgContent, "ticket").takeIf { it.isNotEmpty() }
                    ?: extractXmlTag(msgContent, "ticket")
                val sceneStr = extractXmlAttr(msgContent, "scene").takeIf { it.isNotEmpty() }
                    ?: extractXmlTag(msgContent, "scene")
                val scene = sceneStr.toIntOrNull() ?: 0

                JavaEngine.executeAllOnNewFriend(scripts, fromusername, ticket, scene)
            }
        }
    }

    override fun onUpdate(
        table: String,
        values: ContentValues,
        whereClause: String?,
        whereArgs: Array<String>?,
        conflictAlgorithm: Int
    ) {
        if (table != "chatroom") return
        val chatroomName = values.getAsString("chatroomname") ?: return
        val memberCount = values.getAsInteger("memberCount") ?: return
        val memberlist = values.getAsString("memberlist") ?: return
        if (memberlist.isBlank()) return

        val cursor = WeDatabaseApi.rawQuery(
            "SELECT memberlist, memberCount FROM chatroom WHERE chatroomname = ?",
            arrayOf(chatroomName)
        )
        if (cursor.moveToFirst()) {
            val oldMemberCount = cursor.getInt(cursor.getColumnIndexOrThrow("memberCount"))
            val oldMemberListStr = cursor.getString(cursor.getColumnIndexOrThrow("memberlist"))
            cursor.close()

            if (oldMemberCount == 0 || oldMemberListStr.isNullOrBlank()) return

            val oldMembers = oldMemberListStr.split(";").filter { it.isNotBlank() }.toSet()
            val newMembers = memberlist.split(";").filter { it.isNotBlank() }.toSet()

            if (memberCount > oldMemberCount) {
                val joined = newMembers - oldMembers
                joined.forEach { userWxid ->
                    val nickname = WeDatabaseApi.getDisplayName(userWxid)
                    JavaEngine.executeAllOnMemberChange(scripts, "join", chatroomName, userWxid, nickname)
                }
            } else if (memberCount < oldMemberCount) {
                val left = oldMembers - newMembers
                left.forEach { userWxid ->
                    val nickname = WeDatabaseApi.getDisplayName(userWxid)
                    JavaEngine.executeAllOnMemberChange(scripts, "left", chatroomName, userWxid, nickname)
                }
            }
        }
    }
}
