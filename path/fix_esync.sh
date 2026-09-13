#!/bin/bash
# esync 修复脚本 for Termux

set -e

echo "开始修复 esync 支持..."

# 1. 修复 dlls/ntdll/unix/esync.c
if [ -f "dlls/ntdll/unix/esync.c" ]; then
    echo "修复 dlls/ntdll/unix/esync.c..."
    
    # 备份原文件
    cp dlls/ntdll/unix/esync.c dlls/ntdll/unix/esync.c.backup
    
    # 第一步：修改变量声明
    sed -i 's/static char shm_name\[29\];/static char shm_name[200];\nstatic int termux_esync;/' dlls/ntdll/unix/esync.c
    
    # 第二步：修改路径生成逻辑 - 使用临时文件处理多行替换
    cat > /tmp/esync_patch1.sed << 'EOF'
/if (stat( config_dir, \&st ) == -1)/,/sprintf( shm_name, "\/wine-%lx-esync", (unsigned long)st.st_ino );/c\
    if (stat( config_dir, \&st ) == -1)\
        ERR("Cannot stat %s\\n", config_dir);\
    \
    termux_esync = getenv("WINEESYNC_TERMUX") && atoi(getenv("WINEESYNC_TERMUX"));\
    \
    if (termux_esync)\
    {\
        if (st.st_ino != (unsigned long)st.st_ino)\
            sprintf( shm_name, "/data/data/com.termux/files/usr/tmp/wine-%lx%08lx-esync", (unsigned long)((unsigned long long)st.st_ino >> 32), (unsigned long)st.st_ino );\
        else\
            sprintf( shm_name, "/data/data/com.termux/files/usr/tmp/wine-%lx-esync", (unsigned long)st.st_ino );\
    }\
    else\
    {\
        if (st.st_ino != (unsigned long)st.st_ino)\
            sprintf( shm_name, "/wine-%lx%08lx-esync", (unsigned long)((unsigned long long)st.st_ino >> 32), (unsigned long)st.st_ino );\
        else\
            sprintf( shm_name, "/wine-%lx-esync", (unsigned long)st.st_ino );\
    }
EOF
    
    sed -i -f /tmp/esync_patch1.sed dlls/ntdll/unix/esync.c
    
    # 第三步：修改 shm_open 调用
    sed -i 's/if ((shm_fd = shm_open( shm_name, O_RDWR, 0644 )) == -1)/if ((termux_esync && (shm_fd = open( shm_name, O_RDWR, 0644 )) == -1) || (!termux_esync && (shm_fd = shm_open( shm_name, O_RDWR, 0644 )) == -1))/' dlls/ntdll/unix/esync.c
    
    echo "✅ dlls/ntdll/unix/esync.c 修复完成"
else
    echo "⚠️ dlls/ntdll/unix/esync.c 不存在"
fi

# 2. 修复 programs/winebrowser/main.c
if [ -f "programs/winebrowser/main.c" ]; then
    echo "修复 programs/winebrowser/main.c..."
    # 替换路径
    sed -i 's|"/usr/bin/open"|"/data/data/com.termux/files/usr/glibc/bin/open"|g' programs/winebrowser/main.c
    sed -i 's|L"/usr/bin/open"|L"/data/data/com.termux/files/usr/glibc/bin/open"|g' programs/winebrowser/main.c
    echo "✅ programs/winebrowser/main.c 修复完成"
else
    echo "⚠️ programs/winebrowser/main.c 不存在"
fi

# 3. 修复 server/esync.c
if [ -f "server/esync.c" ]; then
    echo "修复 server/esync.c..."
    # 备份原文件
    cp server/esync.c server/esync.c.backup
    
    # 第一步：修改变量声明
    sed -i 's/static char shm_name\[29\];/static char shm_name[200];\nstatic int termux_esync;/' server/esync.c
    
    # 第二步：修改 shm_cleanup 函数
    sed -i 's/if (shm_unlink( shm_name ) == -1)/if ((termux_esync && unlink( shm_name ) == -1) || (!termux_esync && shm_unlink( shm_name ) == -1))/' server/esync.c
    
    # 第三步：修改路径生成逻辑 - 使用临时文件处理多行替换
    cat > /tmp/esync_patch2.sed << 'EOF'
/if (fstat( config_dir_fd, \&st ) == -1)/,/shm_fd = shm_open( shm_name, O_RDWR | O_CREAT | O_EXCL, 0644 );/c\
    if (fstat( config_dir_fd, \&st ) == -1)\
        fatal_error( "cannot stat config dir\\n" );\
    \
    termux_esync = getenv("WINEESYNC_TERMUX") && atoi(getenv("WINEESYNC_TERMUX"));\
    \
    if (termux_esync)\
    {\
        if (st.st_ino != (unsigned long)st.st_ino)\
            sprintf( shm_name, "/data/data/com.termux/files/usr/tmp/wine-%lx%08lx-esync", (unsigned long)((unsigned long long)st.st_ino >> 32), (unsigned long)st.st_ino );\
        else\
            sprintf( shm_name, "/data/data/com.termux/files/usr/tmp/wine-%lx-esync", (unsigned long)st.st_ino );\
        unlink( shm_name );\
        shm_fd = open( shm_name, O_RDWR | O_CREAT | O_EXCL, 0644 );\
    }\
    else\
    {\
        if (st.st_ino != (unsigned long)st.st_ino)\
            sprintf( shm_name, "/wine-%lx%08lx-esync", (unsigned long)((unsigned long long)st.st_ino >> 32), (unsigned long)st.st_ino );\
        else\
            sprintf( shm_name, "/wine-%lx-esync", (unsigned long)st.st_ino );\
        shm_unlink( shm_name );\
        shm_fd = shm_open( shm_name, O_RDWR | O_CREAT | O_EXCL, 0644 );\
    }
EOF
    
    sed -i -f /tmp/esync_patch2.sed server/esync.c
    
    echo "✅ server/esync.c 修复完成"
else
    echo "⚠️ server/esync.c 不存在"
fi

# 4. 修复 server/unicode.c
if [ -f "server/unicode.c" ]; then
    echo "修复 server/unicode.c..."
    # 替换 nls 目录路径
    sed -i 's|DATADIR "/wine/nls"|DATADIR "/data/data/com.termux/files/usr/glibc/wine/nls"|g' server/unicode.c
    sed -i 's|"/usr/local/share/wine/nls"|"/data/data/com.termux/files/usr/glibc/local/share/wine/nls"|g' server/unicode.c
    sed -i 's|"/usr/share/wine/nls"|"/data/data/com.termux/files/usr/glibc/share/wine/nls"|g' server/unicode.c
    echo "✅ server/unicode.c 修复完成"
else
    echo "⚠️ server/unicode.c 不存在"
fi

echo "esync 修复完成"