package li.songe.gkd.ui.component

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import li.songe.gkd.MainActivity
import li.songe.gkd.ui.share.LocalMainViewModel
import li.songe.gkd.util.ShortUrlSet
import li.songe.gkd.util.throttle


@Composable
fun TermsAcceptDialog() {
    val mainVm = LocalMainViewModel.current
    val context = LocalActivity.current as MainActivity
    val modifier = Modifier.fillMaxWidth()
    val stepDataList = remember {
        arrayOf(
            "使用声明" to @Composable {
                val linkStyles = TextLinkStyles(
                    style = SpanStyle(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                )
                Text(
                    modifier = modifier,
                    text = buildAnnotatedString {
                        append("清屏基于开源 GKD 二次开发。统计默认保存在本机；订阅更新会访问你指定的服务器。高级功能中的快照分享可能上传屏幕内容，请按需使用。上游项目的「")
                        withLink(
                            LinkAnnotation.Url(
                                ShortUrlSet.URL12,
                                linkStyles
                            )
                        ) {
                            append("用户协议")
                        }
                        append("」和「")
                        withLink(
                            LinkAnnotation.Url(
                                ShortUrlSet.URL11,
                                linkStyles
                            )
                        ) {
                            append("隐私政策")
                        }
                        append("」供了解上游功能；本应用不隶属于 GKD 官方。你可以随时暂停自动操作或撤销无障碍权限。")
                    },
                )
            },
            "关于无障碍" to @Composable {
                Text(
                    modifier = modifier,
                    text = "清屏需要无障碍权限读取界面并执行规则允许的点击或返回。规则可能失效或误触；请只导入可信规则。开屏匹配限进入应用后 15 秒，跳转防护需单独开启。隐藏任务卡片不会使服务免于系统清理，也无法在强行停止后保证运行。",
                )
            }
        )
    }
    var step by rememberSaveable { mutableIntStateOf(0) }

    AlertDialog(
        onDismissRequest = {},
        title = {
            Text(text = stepDataList[step].first)
        },
        text = stepDataList[step].second,
        confirmButton = {
            TextButton(onClick = throttle {
                if (step < stepDataList.size - 1) {
                    step++
                } else {
                    mainVm.termsAcceptedFlow.value = true
                }
            }) {
                Text(text = "同意")
            }
        },
        dismissButton = {
            TextButton(onClick = throttle {
                context.finish()
            }) {
                Text(text = "不同意")
            }
        }
    )
}
