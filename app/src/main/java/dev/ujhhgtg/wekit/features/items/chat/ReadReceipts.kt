package dev.ujhhgtg.wekit.features.items.chat

import android.content.Context
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Casino
import com.tencent.mm.pluginsdk.ui.chat.ChatFooter
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.api.ui.WeCurrentConversationApi
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.content.IconButton
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.UuidV4
import dev.ujhhgtg.wekit.utils.android.showToast
import java.util.Base64

@Feature(name = "已读追踪", categories = ["聊天"], description = "追踪文本消息已读人数")
object ReadReceipts : ClickableFeature() {

    private fun encode(input: String): String {
        val bytes = Base64.getUrlEncoder().withoutPadding().encode(input.toByteArray())
        return String(bytes)
    }

    override fun onEnable() {
        ChatInputBarEnhancements.methodSendMessage.hookBefore(100) {
            val chatFooter = thisObject.reflekt().firstField {
                type = ChatFooter::class
            }.get()!! as ChatFooter

            val text = chatFooter.lastText
            if (!text.startsWith(prefix)) return@hookBefore

            val actualText = text.removePrefix(prefix)
            val msgEncoded = encode(actualText)
            val pixelUrl = "$server/pixel?uuid=$uuid&amp;msg=$msgEncoded"

            val escapedText = actualText
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;")

            val target = WeCurrentConversationApi.value

            val xml =
            """
            <msg>
              <appmsg appid="" sdkver="0">
                <title>$escapedText</title>
                <action>view</action>
                <type>57</type>
                <refermsg>
                  <type>49</type>
                  <svrid>3081795456970157299</svrid>
                  <fromusr>wxid_</fromusr>
                  <chatusr>wxid_</chatusr>
                  <displayname> </displayname>
                  <msgsource>&lt;msgsource&gt;&lt;alnode&gt;&lt;fr&gt;2&lt;/fr&gt;&lt;/alnode&gt;&lt;sec_msg_node&gt;&lt;/sec_msg_node&gt;&lt;/msgsource&gt;</msgsource>
                  <content>&lt;msg&gt;&lt;appmsg&#x20;appid=&quot;&quot;&#x20;sdkver=&quot;0&quot;&gt;&lt;title&gt;当前版本不支持展示该内容，请升级至最新版本。&lt;/title&gt;&lt;action&gt;view&lt;/action&gt;&lt;type&gt;51&lt;/type&gt;&lt;url&gt;https://support.weixin.qq.com/security/readtemplate?t=w_security_center_website/upgrade&lt;/url&gt;&lt;finderFeed&gt;&lt;objectId&gt;14667626555619936481&lt;/objectId&gt;&lt;objectNonceId&gt;8625307247096037618_0_12_2_1_1748600110424042_f7dd7f2e-3d3e-11f0-adb0-43719c7e1fc7&lt;/objectNonceId&gt;&lt;feedType&gt;4&lt;/feedType&gt;&lt;username&gt;v2_060000231003b20faec8cae38d1ac4d6c800e435b077830e54ceb941efb42210f69f736d359b@finder&lt;/username&gt;&lt;avatar&gt;&lt;![CDATA[https://wx.qlogo.cn/finderhead/ver_1/MiawsaiaO8qpgTJBRD70ROuXN6En8LoKZ266tvlLeRGRHbb7CvcqKrxH19a2mxiafeuCoakYZhsf1u3AYEB3BooKZ6lpCfRVnsfjMfMHC4ibR67iaV6rR4qZ5Irmal16AFpQ0/0]]&gt;&lt;/avatar&gt;&lt;desc&gt;(⃔&amp;#x20;*`꒳´&amp;#x20;*&amp;#x20; )⃕↝&lt;/desc&gt;&lt;mediaCount&gt;1&lt;/mediaCount&gt;&lt;authIconType&gt;1&lt;/authIconType&gt;&lt;authIconUrl&gt;&lt;![CDATA[https://dldir1v6.qq.com/weixin/checkresupdate/auth_icon_level3_2e2f94615c1e4651a25a7e0446f63135.png]]&gt;&lt;/authIconUrl&gt;&lt;mediaList&gt;&lt;media&gt;&lt;mediaType&gt;4&lt;/mediaType&gt;&lt;url&gt;&lt;![CDATA[http://wxapp.tc.qq.com/251/20302/stodownload?encfilekey=rjD5jyTuFrIpZ2ibE8T7YmwgiahniaXswqz0uUhqGrF2B7C1FqN4dW4RUFEqbMlm05rmPXfSmjgCf3G9ia8ia5kibCH5kxIczTrbCbgAqYUvKicB0IA1udGCuzXpw&amp;hy=SH&amp;idx=1&amp;m=&amp;uzid=7a15c&amp;token=cztXnd9GyrE6cgMDsjj0eZ1MdRB3Eib2ic7rNkGkF4Z9FR5nuld6Yiap9VEugIeCegbHKzjOSMHy5EPTzfChDe3YZJjiaR7aiaFbEzmJ7lsaIjCkSIMxuHkzHibDgX42h1Lq3VySAfoEl06sU0vskxMYumKLA4llQm1WU2hX00ItegJ0c&amp;basedata=CAESBnhXVDE1MRoGeFdUMTExGgZ4V1QxMTIaBnhXVDE1MxoGeFdUMTU2GgZ4V1QxNTEaBnhXVDE1NxoGeFdUMTU4IhgKCgoGeFdUMTEyEAEKCgoGeFdUMTU3EAEqBwiYHRAAGAI&amp;sign=60es22k_sbg7L-LeRKkcDVtXNMBrP54gaTyqCSSs7KRwQm_cI792BPZxaghvauP9954aUbkgAXldv-6hcaDvjA&amp;ctsc=12&amp;extg=10eb900&amp;svrbypass=AAuL%2FQsFAAABAAAAAAC%2B28t6CjV1pwlsLoU5aBAAAADnaHZTnGbFfAj9RgZXfw6Vfkx7FpiL%2B22LVp4HLkn05tij40%2FAsJD%2BPQrMho6FgQX6w1ETaBHqHtM%3D&amp;svrnonce=1748600110]]&gt;&lt;/url&gt;&lt;thumbUrl&gt;&lt;![CDATA[$pixelUrl]]&gt;&lt;/thumbUrl&gt;&lt;coverUrl&gt;&lt;![CDATA[$pixelUrl]]&gt;&lt;/coverUrl&gt;&lt;width&gt;1080.0&lt;/width&gt;&lt;height&gt;1920.0&lt;/height&gt;&lt;videoPlayDuration&gt;8&lt;/videoPlayDuration&gt;&lt;/media&gt;&lt;/mediaList&gt;&lt;sourceCommentScene&gt;1&lt;/sourceCommentScene&gt;&lt;finderShareExtInfo&gt;&lt;![CDATA[{&quot;hasInput&quot;:false,&quot;tabContextId&quot;:&quot;4-1748600105044&quot;,&quot;contextId&quot;:&quot;1-1-17-e669331b7d4243ecae426b3a64ec81b5&quot;,&quot;shareSrcScene&quot;:4}]]&gt;&lt;/finderShareExtInfo&gt;&lt;/finderFeed&gt;&lt;/appmsg&gt;&lt;/msg&gt;</content>
                  <createtime>1748600455</createtime>
                </refermsg>
              </appmsg>
            </msg>
            """.trimIndent()

            WeMessageApi.sendXmlAppMsg(target, xml)
            showToast(chatFooter.context, "已发送附带已读追踪的消息")

            chatFooter.lastText = ""

            result = null
        }
    }

    private var prefix by prefOption("read_receipts_prefix", "#")
    private var server by prefOption("read_receipts_server", "")
    private var uuid by prefOption("read_receipts_uuid", UuidV4.random().toString())

    override fun onClick(context: Context) {
        showComposeDialog(context) {
            var serverInput by remember { mutableStateOf(server) }
            var prefixInput by remember { mutableStateOf(prefix) }
            var uuidInput by remember { mutableStateOf(uuid) }

            AlertDialogContent(title = { Text("已读追踪") },
                text = {
                    DefaultColumn {
                        TextField(
                            value = serverInput,
                            onValueChange = { serverInput = it },
                            label = { Text("服务器") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        TextField(
                            value = prefixInput,
                            onValueChange = { prefixInput = it },
                            label = { Text("触发前缀") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextField(
                                value = uuidInput,
                                onValueChange = { uuidInput = it },
                                label = { Text("UUID") },
                                modifier = Modifier.weight(1f))
                            IconButton(onClick = { uuidInput = UuidV4.random().toString() }) {
                                Icon(
                                    imageVector = MaterialSymbols.Outlined.Casino,
                                    contentDescription = "Generate Random UUID")
                            }
                        }
                    }
                },
                dismissButton = { TextButton(onDismiss) { Text("取消") } },
                confirmButton = { Button(onClick = {
                    if (serverInput.isBlank()) {
                        showToast(context, "错误: 未设置服务器!")
                        return@Button
                    }
                    server = serverInput

                    if (prefixInput.isEmpty()) {
                        showToast(context, "警告: 「触发前缀」为空, 所有文本消息将启用已读追踪!")
                    }
                    prefix = prefixInput

                    uuid = uuidInput

                    onDismiss()
                }) { Text("确定") } })
        }
    }
}
