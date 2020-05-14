# CloudPhoneProject
改写scrcpy（  https://github.com/Genymobile/scrcpy  ）的app端的代码，通过netty搭建socket环境，使得手机屏幕的画面在网页上显示，并能够进行简单的操作

此方式web显示有点卡，并且有点模糊

mediacodec解码成yuv后，web端显示很卡，还不如minicap，所以放弃

最后还是用客户端显示，内部使用的工具，能用就行
