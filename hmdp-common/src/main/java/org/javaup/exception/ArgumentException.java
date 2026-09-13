/**
 * @(#)ParameterException.java 2011-12-20 Copyright 2011 it.kedacom.com, Inc.
 *                             All rights reserved.
 */

package org.javaup.exception;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * @program: 黑马点评-plus升级版实战项目。添加 阿星不是程序员 微信，添加时备注 点评 来获取项目的完整资料
 * @description: 参数异常
 * @author: 阿星不是程序员
 **/

@EqualsAndHashCode(callSuper = true)
@Data
public class ArgumentException extends BaseException {
	
	private List<ArgumentError> argumentErrorList;
	
	public ArgumentException(List<ArgumentError> argumentErrorList) {
		this.argumentErrorList = argumentErrorList;
	}

	public ArgumentException(String message) {
		super(message);
	}
	

	public ArgumentException(Throwable cause) {
		super(cause);
	}

	public ArgumentException(String message, Throwable cause) {
		super(message, cause);
	}
}
