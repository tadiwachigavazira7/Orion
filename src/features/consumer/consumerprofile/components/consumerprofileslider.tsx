import React from 'react';
import { Image, Pressable, StyleSheet, Text, View } from 'react-native';
import { theme } from '../../../../theme';

interface SliderProps {
  activeTab: string;
  onTabChange: (tab: 'Edit Profile' | 'Payment Methods' | 'Purchases') => void;
  firstName: string;
  lastName: string;
}

export function ConsumerProfileSlider({ activeTab, onTabChange, firstName, lastName }: SliderProps) {
  const tabs: ('Edit Profile' | 'Payment Methods' | 'Purchases')[] = [
    'Edit Profile', 
    'Payment Methods', 
    'Purchases'
  ];

  // Dynamic Initials Calculation
  const firstInitial = firstName ? firstName.charAt(0).toUpperCase() : '';
  const lastInitial = lastName ? lastName.charAt(0).toUpperCase() : '';
  const initials = (firstInitial + lastInitial) || '??';
  const logo = require('../../../../../assets/images/OrionLogo.png');

  return (
    <View style={[styles.headerWrapper, { backgroundColor: theme.background }]}>
      
      {/* Orion Logo Header Block */}
      <View style={styles.brandRow}>
        <Image source={logo} style={styles.logoImage} resizeMode="contain" />
      </View>

      {/* Profile Overview Meta Information Row */}
      <View style={styles.profileMetaRow}>
        <View style={[styles.avatarCircle, { backgroundColor: theme.card === 'white' ? '#FAFAFA' : theme.card }]}>
          <Text style={[styles.avatarText, { color: theme.text }]}>{initials}</Text>
        </View>
        
        <View style={styles.planDetails}>
          <Text style={[styles.planTitleText, { color: theme.text }]}>Plan: Consumer Basic</Text>
          <Pressable onPress={() => console.log('Change Plan Clicked')}>
            <Text style={[styles.changePlanText, { color: theme.text }]}>Change Plan</Text>
          </Pressable>
        </View>
      </View>

      {/* Interactive Underlying Tab Buttons Row Layout */}
      <View style={[styles.tabsContainer, { borderBottomColor: theme.border === 'black' ? '#CCCCCC' : '#444444' }]}>
        {tabs.map((tab) => {
          const isActive = activeTab === tab;
          return (
            <Pressable
              key={tab}
              onPress={() => onTabChange(tab)}
              style={styles.tabButton}
            >
              <Text style={[styles.tabText, { color: theme.subtext }, isActive && styles.activeTabText, isActive && { color: theme.text }]}>
                {tab}
              </Text>
              <View style={[styles.underline, isActive && { backgroundColor: theme.border }]} />
            </Pressable>
          );
        })}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  headerWrapper: {
    width: '100%',
    paddingTop: 10,
  },
  brandRow: {
    width: '100%',
    flexDirection: 'row',
    justifyContent: 'flex-end',
    paddingHorizontal: 4,
    marginBottom: 5,
  },
  logoImage: {
    height: 100, 
    width: 100, 
    marginTop: -25,
  },
  profileMetaRow: {
    flexDirection: 'row',
    alignItems: 'center',
    width: '100%',
    paddingHorizontal: 4,
    marginBottom: 20,
  },
  avatarCircle: {
    width: 85,
    height: 85,
    borderRadius: 42.5,
    alignItems: 'center',
    justifyContent: 'center',
    marginRight: 20,
  },
  avatarText: {
    fontSize: 28,
    fontWeight: '500',
  },
  planDetails: {
    justifyContent: 'center',
  },
  planTitleText: {
    fontSize: 19,
    fontWeight: '500',
    marginBottom: 4,
  },
  changePlanText: {
    fontSize: 13,
    fontWeight: '600',
    fontStyle: 'italic',
    textDecorationLine: 'underline',
  },
  tabsContainer: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    width: '100%',
    borderBottomWidth: 1,
  },
  tabButton: {
    flex: 1,
    alignItems: 'center',
  },
  tabText: {
    fontSize: 14,
    fontWeight: '600',
    paddingBottom: 8,
  },
  activeTabText: {
    fontWeight: '700',
  },
  underline: {
    height: 2,
    width: '100%',
    backgroundColor: 'transparent',
  },
});