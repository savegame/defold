# Copyright 2020-2026 The Defold Foundation
# Copyright 2014-2020 King
# Copyright 2009-2014 Ragnar Svensson, Christian Murray
# Licensed under the Defold License version 1.0 (the "License"); you may not use
# this file except in compliance with the License.
#
# You may obtain a copy of the License, together with FAQs at
# https://www.defold.com/license
#
# Unless required by applicable law or agreed to in writing, software distributed
# under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
# CONDITIONS OF ANY KIND, either express or implied. See the License for the
# specific language governing permissions and limitations under the License.

# AURORA PORT: копия апстримного share/ext/common_private.sh с добавленной
# платформой arm64-aurora / armv7-aurora. Это официальная точка расширения:
# cmi_setup_cc() и cmi() в common.sh уходят сюда для любой платформы, которой
# они не знают, поэтому апстримный common.sh править не нужно.
#
# Файл ставится в дерево Defold скриптом scripts/defold/setup.sh --overlay.

function cmi_setup_cc_private() {
    case $1 in
        arm64-aurora|armv7-aurora)
            # Внутри `sb2 -m sdk-build` компилятор УЖЕ целевой, а заголовки и
            # библиотеки берутся из rootfs таргета. Поэтому здесь не должно быть
            # ни --target=, ни --sysroot=, ни подстановки хостового clang —
            # иначе получим кросс-сборку поверх кросс-сборки.
            #
            # WL_EGL_PLATFORM обязателен: без него EGL/eglplatform.h уходит
            # в X11-ветку и тянет X11/Xlib.h, которого в таргете нет.
            export CFLAGS="${CFLAGS} -fPIC -DWL_EGL_PLATFORM"
            export CXXFLAGS="${CXXFLAGS} -fPIC -DWL_EGL_PLATFORM"
            export CPPFLAGS="${CPPFLAGS} -fPIC -DWL_EGL_PLATFORM"
            ;;

        *)
            echo "Checking for supported private platforms"
            ;;
    esac
}

function cmi_private() {
    export PREFIX=`pwd`/build
    export PLATFORM=$1

    case $PLATFORM in
        arm64-nx64)
            echo "Has arm64-nx64 support"
            cmi_cross $PLATFORM $PLATFORM
            ;;

        arm64-aurora|armv7-aurora)
            # Внутри sb2 сборка нативная для таргета, поэтому cmi_buildplatform,
            # а не cmi_cross: configure-скриптам не нужен --host=.
            cmi_buildplatform $PLATFORM
            ;;

        *)
            echo "Unknown target $1" && exit 1
            ;;
    esac
}
